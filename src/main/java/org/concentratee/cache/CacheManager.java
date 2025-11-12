package org.concentratee.cache;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.pubsub.PgSubscriber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.concentratee.cache.model.Profile;
import org.concentratee.cache.model.Rule;
import org.concentratee.cache.model.Session;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class CacheManager {

    private static final Logger LOG = Logger.getLogger(CacheManager.class);

    @Inject
    PgPool client;

    @Inject
    Vertx vertx;

    // Concurrent hash maps for caching
    private final ConcurrentHashMap<Long, Session> sessionsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Rule> rulesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Profile> profilesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> studentsById = new ConcurrentHashMap<>(); // studentId -> email

    // Derived indexes for fast lookups
    private final ConcurrentHashMap<String, List<Session>> sessionsByEmail = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<Session>> sessionsByProfile = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<Rule>>> rulesByScopeAndValue = new ConcurrentHashMap<>();

    // PostgreSQL subscriber for LISTEN/NOTIFY
    private PgSubscriber subscriber;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("🚀 Initializing cache from database...");

        // Ensure database triggers exist
        ensureTriggersExist()
            .chain(() -> loadStudents())
            .chain(() -> {
                // Load all other data in parallel
                return Uni.combine().all().unis(
                    loadProfiles(),
                    loadRules(),
                    loadSessions()
                ).asTuple();
            })
            .chain(() -> {
                // Build derived indexes after data is loaded
                return Uni.combine().all().unis(
                    buildSessionsByEmailIndex(),
                    buildSessionsByProfileIndex(),
                    buildRulesByScopeAndValueIndex()
                ).asTuple();
            })
            .subscribe().with(
                v -> {
                    LOG.info("✅ Cache loaded successfully!");
                    LOG.info("  - Students: " + studentsById.size());
                    LOG.info("  - Profiles: " + profilesById.size());
                    LOG.info("  - Rules: " + rulesById.size());
                    LOG.info("  - Sessions: " + sessionsById.size());
                    LOG.info("  - Sessions by email: " + sessionsByEmail.size());
                    LOG.info("  - Sessions by profile: " + sessionsByProfile.size());
                    LOG.info("  - Rules by scope: " + rulesByScopeAndValue.size() + " scopes indexed");

                    // Setup database change listeners
                    setupDatabaseListeners();
                },
                failure -> LOG.error("❌ Failed to load cache: " + failure.getMessage(), failure)
            );
    }

    private Uni<Void> ensureTriggersExist() {
        String sql = """
            -- Create notification functions if they don't exist
            CREATE OR REPLACE FUNCTION notify_student_changes()
            RETURNS trigger AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM pg_notify('students_changes', json_build_object('operation', TG_OP, 'id', OLD.id)::text);
                    RETURN OLD;
                ELSE
                    PERFORM pg_notify('students_changes', json_build_object('operation', TG_OP, 'id', NEW.id, 'feide_email', NEW.feide_email)::text);
                    RETURN NEW;
                END IF;
            END;
            $$ LANGUAGE plpgsql;

            CREATE OR REPLACE FUNCTION notify_profile_changes()
            RETURNS trigger AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM pg_notify('profiles_changes', json_build_object('operation', TG_OP, 'id', OLD.id)::text);
                    RETURN OLD;
                ELSE
                    PERFORM pg_notify('profiles_changes', json_build_object('operation', TG_OP, 'id', NEW.id, 'name', NEW.name, 'domains', NEW.domains, 'teacher_id', NEW.teacher_id, 'school_id', NEW.school_id, 'is_whitelist_url', NEW.is_whitelist_url)::text);
                    RETURN NEW;
                END IF;
            END;
            $$ LANGUAGE plpgsql;

            CREATE OR REPLACE FUNCTION notify_rule_changes()
            RETURNS trigger AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM pg_notify('rules_changes', json_build_object('operation', TG_OP, 'id', OLD.id)::text);
                    RETURN OLD;
                ELSE
                    PERFORM pg_notify('rules_changes', json_build_object('operation', TG_OP, 'id', NEW.id, 'scope', NEW.scope, 'scope_value', NEW.scope_value, 'start_time', NEW.start_time, 'end_time', NEW.end_time, 'profile_id', NEW.profile_id)::text);
                    RETURN NEW;
                END IF;
            END;
            $$ LANGUAGE plpgsql;

            CREATE OR REPLACE FUNCTION notify_session_changes()
            RETURNS trigger AS $$
            DECLARE
                student_email TEXT;
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    SELECT feide_email INTO student_email FROM students WHERE id = OLD.student_id;
                    PERFORM pg_notify('sessions_changes', json_build_object('operation', TG_OP, 'id', OLD.id, 'student_id', OLD.student_id, 'student_email', student_email)::text);
                    RETURN OLD;
                ELSE
                    SELECT feide_email INTO student_email FROM students WHERE id = NEW.student_id;
                    PERFORM pg_notify('sessions_changes', json_build_object('operation', TG_OP, 'id', NEW.id, 'title', NEW.title, 'start_time', NEW.start_time, 'end_time', NEW.end_time, 'student_id', NEW.student_id, 'student_email', student_email, 'class_id', NEW.class_id, 'teacher_id', NEW.teacher_id, 'school_id', NEW.school_id, 'teacher_session_id', NEW.teacher_session_id, 'grade', NEW.grade, 'profile_id', NEW.profile_id, 'is_active', NEW.is_active, 'percentage', NEW.percentage)::text);
                    RETURN NEW;
                END IF;
            END;
            $$ LANGUAGE plpgsql;

            -- Create triggers if they don't exist
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'student_changes_trigger') THEN
                    CREATE TRIGGER student_changes_trigger
                    AFTER INSERT OR UPDATE OR DELETE ON students
                    FOR EACH ROW EXECUTE FUNCTION notify_student_changes();
                END IF;

                IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'profile_changes_trigger') THEN
                    CREATE TRIGGER profile_changes_trigger
                    AFTER INSERT OR UPDATE OR DELETE ON profiles
                    FOR EACH ROW EXECUTE FUNCTION notify_profile_changes();
                END IF;

                IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'rule_changes_trigger') THEN
                    CREATE TRIGGER rule_changes_trigger
                    AFTER INSERT OR UPDATE OR DELETE ON rules
                    FOR EACH ROW EXECUTE FUNCTION notify_rule_changes();
                END IF;

                IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'session_changes_trigger') THEN
                    CREATE TRIGGER session_changes_trigger
                    AFTER INSERT OR UPDATE OR DELETE ON sessions
                    FOR EACH ROW EXECUTE FUNCTION notify_session_changes();
                END IF;
            END $$;
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                LOG.info("✅ Database triggers verified/created");
                return null;
            })
            .onFailure().invoke(failure ->
                LOG.error("❌ Failed to create triggers: " + failure.getMessage(), failure)
            )
            .replaceWithVoid();
    }

    private Uni<Void> loadStudents() {
        String sql = """
            SELECT id, feide_email
            FROM students
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                int count = 0;
                for (Row row : rows) {
                    Long id = row.getLong("id");
                    String email = row.getString("feide_email");
                    if (email != null) {
                        studentsById.put(id, email);
                        count++;
                    }
                }
                LOG.debug("Loaded " + count + " students");
                return null;
            });
    }

    private Uni<Void> loadProfiles() {
        String sql = """
            SELECT p.id, p.name, p.domains, p.teacher_id, p.school_id, p.is_whitelist_url
            FROM profiles p
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                int count = 0;
                for (Row row : rows) {
                    Profile profile = new Profile();
                    profile.id = row.getLong("id");
                    profile.name = row.getString("name");
                    profile.teacherId = row.getLong("teacher_id");
                    profile.schoolId = row.getLong("school_id");
                    profile.isWhitelistUrl = row.getBoolean("is_whitelist_url");

                    // Parse JSONB domains array
                    String domainsJson = row.get(Object.class, "domains").toString();
                    profile.domains = parseDomainsFromJson(domainsJson);

                    profilesById.put(profile.id, profile);
                    count++;
                }
                LOG.debug("Loaded " + count + " profiles");
                return null;
            });
    }

    private Uni<Void> loadRules() {
        String sql = """
            SELECT id, scope, scope_value, start_time, end_time, profile_id
            FROM rules
            WHERE start_time <= CURRENT_DATE AND end_time >= CURRENT_DATE
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                int count = 0;
                for (Row row : rows) {
                    Rule rule = new Rule();
                    rule.id = row.getLong("id");
                    rule.scope = row.getString("scope");
                    rule.scopeValue = row.getString("scope_value");
                    rule.startTime = row.getLocalDateTime("start_time");
                    rule.endTime = row.getLocalDateTime("end_time");
                    rule.profileId = row.getLong("profile_id");

                    rulesById.put(rule.id, rule);
                    count++;
                }
                LOG.debug("Loaded " + count + " rules");
                return null;
            });
    }

    private Uni<Void> loadSessions() {
        String sql = """
            SELECT id, title, start_time, end_time, student_id, class_id,
                   teacher_id, school_id, teacher_session_id, grade, profile_id,
                   is_active, percentage
            FROM sessions
            WHERE DATE(start_time) = CURRENT_DATE
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                int count = 0;
                for (Row row : rows) {
                    Session session = new Session();
                    session.id = row.getLong("id");
                    session.title = row.getString("title");
                    session.startTime = row.getLocalDateTime("start_time");
                    session.endTime = row.getLocalDateTime("end_time");
                    session.studentId = row.getLong("student_id");
                    session.classId = row.getLong("class_id");
                    session.teacherId = row.getLong("teacher_id");
                    session.schoolId = row.getLong("school_id");
                    session.teacherSessionId = row.getLong("teacher_session_id");
                    session.grade = row.getInteger("grade");
                    session.profileId = row.getLong("profile_id");
                    session.isActive = row.getBoolean("is_active");
                    session.percentage = row.getDouble("percentage");

                    // Look up email from students hash
                    session.studentEmail = studentsById.get(session.studentId);

                    sessionsById.put(session.id, session);
                    count++;
                }

                LOG.debug("Loaded " + count + " sessions");
                return null;
            });
    }

    private Uni<Void> buildSessionsByEmailIndex() {
        return Uni.createFrom().item(() -> {
            Map<String, List<Session>> tempMap = new HashMap<>();

            for (Session session : sessionsById.values()) {
                if (session.studentEmail != null) {
                    tempMap.computeIfAbsent(session.studentEmail, k -> new ArrayList<>())
                           .add(session);
                }
            }

            sessionsByEmail.putAll(tempMap);
            LOG.debug("Built sessionsByEmail index for " + tempMap.size() + " students");
            return null;
        });
    }

    private Uni<Void> buildSessionsByProfileIndex() {
        return Uni.createFrom().item(() -> {
            Map<Long, List<Session>> tempMap = new HashMap<>();

            for (Session session : sessionsById.values()) {
                if (session.profileId != null) {
                    tempMap.computeIfAbsent(session.profileId, k -> new ArrayList<>())
                           .add(session);
                }
            }

            sessionsByProfile.putAll(tempMap);
            LOG.debug("Built sessionsByProfile index for " + tempMap.size() + " profiles");
            return null;
        });
    }

    private Uni<Void> buildRulesByScopeAndValueIndex() {
        return Uni.createFrom().item(() -> {
            Map<String, Map<String, List<Rule>>> tempMap = new HashMap<>();

            for (Rule rule : rulesById.values()) {
                if (rule.scope != null && rule.scopeValue != null) {
                    tempMap.computeIfAbsent(rule.scope, k -> new HashMap<>())
                           .computeIfAbsent(rule.scopeValue, k -> new ArrayList<>())
                           .add(rule);
                }
            }

            // Convert to ConcurrentHashMap
            for (Map.Entry<String, Map<String, List<Rule>>> entry : tempMap.entrySet()) {
                ConcurrentHashMap<String, List<Rule>> innerMap = new ConcurrentHashMap<>(entry.getValue());
                rulesByScopeAndValue.put(entry.getKey(), innerMap);
            }

            LOG.debug("Built rulesByScopeAndValue index for " + tempMap.size() + " scopes");
            return null;
        });
    }

    private void setupDatabaseListeners() {
        LOG.info("🔊 Setting up PostgreSQL LISTEN/NOTIFY...");

        // Create PgSubscriber connection
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost("localhost")
            .setPort(5432)
            .setDatabase("concentratee_dev")
            .setUser("postgres")
            .setPassword("postgres");

        subscriber = PgSubscriber.subscriber(vertx.getDelegate(), connectOptions);

        // Connect and setup listeners
        subscriber.connect(ar -> {
            if (ar.succeeded()) {
                LOG.info("✅ Connected to PostgreSQL for LISTEN/NOTIFY");

                // Listen to profiles_changes channel
                subscriber.channel("profiles_changes").handler(payload -> {
                    LOG.info("📢 Profile change detected: " + payload);
                    handleProfileChange(payload);
                });

                // Listen to rules_changes channel
                subscriber.channel("rules_changes").handler(payload -> {
                    LOG.info("📢 Rule change detected: " + payload);
                    handleRuleChange(payload);
                });

                // Listen to sessions_changes channel
                subscriber.channel("sessions_changes").handler(payload -> {
                    LOG.info("📢 Session change detected: " + payload);
                    handleSessionChange(payload);
                });

                // Listen to students_changes channel
                subscriber.channel("students_changes").handler(payload -> {
                    LOG.info("📢 Student change detected: " + payload);
                    handleStudentChange(payload);
                });

                LOG.info("  Channels: students_changes, sessions_changes, rules_changes, profiles_changes");
            } else {
                LOG.error("❌ Failed to setup LISTEN/NOTIFY: " + ar.cause().getMessage());
            }
        });
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        if (subscriber != null) {
            subscriber.close();
            LOG.info("🔌 Closed PostgreSQL LISTEN/NOTIFY connection");
        }
    }

    private void handleProfileChange(String payload) {
        // Parse JSON payload and update cache
        // For now, just reload the profile
        try {
            // Simple parsing - in production use proper JSON library
            if (payload.contains("\"operation\":\"DELETE\"")) {
                Long id = extractId(payload);
                profilesById.remove(id);
                LOG.info("  ➡️ Removed profile " + id + " from cache");
            } else {
                Long id = extractId(payload);
                // Reload profile from database
                loadProfileById(id).subscribe().with(
                    v -> LOG.info("  ➡️ Updated profile " + id + " in cache"),
                    failure -> LOG.error("Failed to reload profile " + id, failure)
                );
            }
        } catch (Exception e) {
            LOG.error("Failed to handle profile change: " + e.getMessage(), e);
        }
    }

    private void handleRuleChange(String payload) {
        try {
            if (payload.contains("\"operation\":\"DELETE\"")) {
                Long id = extractId(payload);
                Rule rule = rulesById.remove(id);
                if (rule != null && rule.scope != null && rule.scopeValue != null) {
                    // Remove from scope index
                    rulesByScopeAndValue.computeIfPresent(rule.scope, (scope, valueMap) -> {
                        valueMap.computeIfPresent(rule.scopeValue, (value, rules) -> {
                            rules.removeIf(r -> r.id.equals(id));
                            return rules.isEmpty() ? null : rules;
                        });
                        return valueMap.isEmpty() ? null : valueMap;
                    });
                }
                LOG.info("  ➡️ Removed rule " + id + " from cache");
            } else {
                Long id = extractId(payload);
                loadRuleById(id).subscribe().with(
                    v -> LOG.info("  ➡️ Updated rule " + id + " in cache"),
                    failure -> LOG.error("Failed to reload rule " + id, failure)
                );
            }
        } catch (Exception e) {
            LOG.error("Failed to handle rule change: " + e.getMessage(), e);
        }
    }

    private void handleSessionChange(String payload) {
        try {
            if (payload.contains("\"operation\":\"DELETE\"")) {
                Long id = extractId(payload);
                Session session = sessionsById.remove(id);
                if (session != null) {
                    // Remove from email index
                    if (session.studentEmail != null) {
                        sessionsByEmail.computeIfPresent(session.studentEmail, (email, sessions) -> {
                            sessions.removeIf(s -> s.id.equals(id));
                            return sessions.isEmpty() ? null : sessions;
                        });
                    }
                    // Remove from profile index
                    if (session.profileId != null) {
                        sessionsByProfile.computeIfPresent(session.profileId, (profileId, sessions) -> {
                            sessions.removeIf(s -> s.id.equals(id));
                            return sessions.isEmpty() ? null : sessions;
                        });
                    }
                }
                LOG.info("  ➡️ Removed session " + id + " from cache");
            } else {
                Long id = extractId(payload);
                loadSessionById(id).subscribe().with(
                    v -> LOG.info("  ➡️ Updated session " + id + " in cache"),
                    failure -> LOG.error("Failed to reload session " + id, failure)
                );
            }
        } catch (Exception e) {
            LOG.error("Failed to handle session change: " + e.getMessage(), e);
        }
    }

    private void handleStudentChange(String payload) {
        try {
            if (payload.contains("\"operation\":\"DELETE\"")) {
                Long id = extractId(payload);
                String oldEmail = studentsById.remove(id);
                if (oldEmail != null) {
                    // Remove all sessions for this student from sessionsByEmail
                    sessionsByEmail.remove(oldEmail);
                    // Update all sessions in sessionsById that had this student
                    sessionsById.values().stream()
                        .filter(s -> s.studentId != null && s.studentId.equals(id))
                        .forEach(s -> s.studentEmail = null);
                }
                LOG.info("  ➡️ Removed student " + id + " from cache");
            } else {
                Long id = extractId(payload);
                loadStudentById(id).subscribe().with(
                    v -> LOG.info("  ➡️ Updated student " + id + " in cache"),
                    failure -> LOG.error("Failed to reload student " + id, failure)
                );
            }
        } catch (Exception e) {
            LOG.error("Failed to handle student change: " + e.getMessage(), e);
        }
    }

    private Long extractId(String json) {
        // Simple extraction - in production use proper JSON library
        // Handle both "id":123 and "id" : 123 formats
        int idIndex = json.indexOf("\"id\"");
        if (idIndex == -1) return null;

        int colonIndex = json.indexOf(":", idIndex);
        int startIndex = colonIndex + 1;

        // Skip whitespace
        while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
            startIndex++;
        }

        // Find end (comma or closing brace)
        int endIndex = startIndex;
        while (endIndex < json.length() &&
               Character.isDigit(json.charAt(endIndex))) {
            endIndex++;
        }

        String idStr = json.substring(startIndex, endIndex).trim();
        return Long.parseLong(idStr);
    }

    private Uni<Void> loadProfileById(Long id) {
        String sql = """
            SELECT p.id, p.name, p.domains, p.teacher_id, p.school_id, p.is_whitelist_url
            FROM profiles p
            WHERE p.id = $1
            """;

        return client.preparedQuery(sql).execute(io.vertx.mutiny.sqlclient.Tuple.of(id))
            .onItem().transform(rows -> {
                for (Row row : rows) {
                    Profile profile = new Profile();
                    profile.id = row.getLong("id");
                    profile.name = row.getString("name");
                    profile.teacherId = row.getLong("teacher_id");
                    profile.schoolId = row.getLong("school_id");
                    profile.isWhitelistUrl = row.getBoolean("is_whitelist_url");
                    String domainsJson = row.get(Object.class, "domains").toString();
                    profile.domains = parseDomainsFromJson(domainsJson);
                    profilesById.put(profile.id, profile);
                }
                return null;
            });
    }

    private Uni<Void> loadRuleById(Long id) {
        String sql = """
            SELECT id, scope, scope_value, start_time, end_time, profile_id
            FROM rules
            WHERE id = $1
            """;

        return client.preparedQuery(sql).execute(io.vertx.mutiny.sqlclient.Tuple.of(id))
            .onItem().transform(rows -> {
                // First remove old rule from index if it exists
                Rule oldRule = rulesById.get(id);
                if (oldRule != null && oldRule.scope != null && oldRule.scopeValue != null) {
                    rulesByScopeAndValue.computeIfPresent(oldRule.scope, (scope, valueMap) -> {
                        valueMap.computeIfPresent(oldRule.scopeValue, (value, rules) -> {
                            rules.removeIf(r -> r.id.equals(id));
                            return rules.isEmpty() ? null : rules;
                        });
                        return valueMap.isEmpty() ? null : valueMap;
                    });
                }

                // Add new/updated rule
                for (Row row : rows) {
                    Rule rule = new Rule();
                    rule.id = row.getLong("id");
                    rule.scope = row.getString("scope");
                    rule.scopeValue = row.getString("scope_value");
                    rule.startTime = row.getLocalDateTime("start_time");
                    rule.endTime = row.getLocalDateTime("end_time");
                    rule.profileId = row.getLong("profile_id");
                    rulesById.put(rule.id, rule);

                    // Add to scope index
                    if (rule.scope != null && rule.scopeValue != null) {
                        rulesByScopeAndValue.computeIfAbsent(rule.scope, k -> new ConcurrentHashMap<>())
                                           .computeIfAbsent(rule.scopeValue, k -> new ArrayList<>())
                                           .add(rule);
                    }
                }
                return null;
            });
    }

    private Uni<Void> loadSessionById(Long id) {
        String sql = """
            SELECT id, title, start_time, end_time, student_id, class_id,
                   teacher_id, school_id, teacher_session_id, grade, profile_id,
                   is_active, percentage
            FROM sessions
            WHERE id = $1
            """;

        return client.preparedQuery(sql).execute(io.vertx.mutiny.sqlclient.Tuple.of(id))
            .onItem().transform(rows -> {
                // First remove old session from indexes if it exists
                Session oldSession = sessionsById.get(id);
                if (oldSession != null) {
                    if (oldSession.studentEmail != null) {
                        sessionsByEmail.computeIfPresent(oldSession.studentEmail, (email, sessions) -> {
                            sessions.removeIf(s -> s.id.equals(id));
                            return sessions.isEmpty() ? null : sessions;
                        });
                    }
                    if (oldSession.profileId != null) {
                        sessionsByProfile.computeIfPresent(oldSession.profileId, (profileId, sessions) -> {
                            sessions.removeIf(s -> s.id.equals(id));
                            return sessions.isEmpty() ? null : sessions;
                        });
                    }
                }

                // Add new/updated session
                for (Row row : rows) {
                    Session session = new Session();
                    session.id = row.getLong("id");
                    session.title = row.getString("title");
                    session.startTime = row.getLocalDateTime("start_time");
                    session.endTime = row.getLocalDateTime("end_time");
                    session.studentId = row.getLong("student_id");
                    session.classId = row.getLong("class_id");
                    session.teacherId = row.getLong("teacher_id");
                    session.schoolId = row.getLong("school_id");
                    session.teacherSessionId = row.getLong("teacher_session_id");
                    session.grade = row.getInteger("grade");
                    session.profileId = row.getLong("profile_id");
                    session.isActive = row.getBoolean("is_active");
                    session.percentage = row.getDouble("percentage");

                    // Look up email from students hash instead of JOIN
                    session.studentEmail = studentsById.get(session.studentId);

                    sessionsById.put(session.id, session);
                    if (session.studentEmail != null) {
                        sessionsByEmail.computeIfAbsent(session.studentEmail, k -> new ArrayList<>())
                                      .add(session);
                    }
                    if (session.profileId != null) {
                        sessionsByProfile.computeIfAbsent(session.profileId, k -> new ArrayList<>())
                                        .add(session);
                    }
                }
                return null;
            });
    }

    private Uni<Void> loadStudentById(Long id) {
        String sql = """
            SELECT id, feide_email
            FROM students
            WHERE id = $1
            """;

        return client.preparedQuery(sql).execute(io.vertx.mutiny.sqlclient.Tuple.of(id))
            .onItem().transform(rows -> {
                String oldEmail = studentsById.get(id);

                for (Row row : rows) {
                    Long studentId = row.getLong("id");
                    String newEmail = row.getString("feide_email");

                    // Update studentsById hash
                    studentsById.put(studentId, newEmail);

                    // Update all sessions that reference this student
                    List<Session> affectedSessions = new ArrayList<>();
                    for (Session session : sessionsById.values()) {
                        if (session.studentId != null && session.studentId.equals(studentId)) {
                            session.studentEmail = newEmail;
                            affectedSessions.add(session);
                        }
                    }

                    // Rebuild sessionsByEmail index for affected emails
                    if (oldEmail != null) {
                        sessionsByEmail.remove(oldEmail);
                    }
                    if (newEmail != null && !affectedSessions.isEmpty()) {
                        sessionsByEmail.put(newEmail, affectedSessions);
                    }
                }
                return null;
            });
    }

    private List<String> parseDomainsFromJson(String json) {
        if (json == null || json.equals("[]")) {
            return new ArrayList<>();
        }
        // Simple JSON array parsing - in production use a proper JSON library
        return Arrays.stream(json
            .replace("[", "")
            .replace("]", "")
            .replace("\"", "")
            .split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    // Public getter methods for accessing cached data
    public List<Session> getSessionsByEmail(String email) {
        List<Session> sessions = sessionsByEmail.get(email);
        if (sessions == null) {
            return Collections.emptyList();
        }

        // Filter to only return today's sessions to prevent stale data
        LocalDate today = LocalDate.now();
        return sessions.stream()
            .filter(s -> s.startTime != null &&
                         s.startTime.toLocalDate().equals(today))
            .collect(Collectors.toList());
    }

    public List<Rule> getRulesByScope(String scope, String scopeValue) {
        ConcurrentHashMap<String, List<Rule>> valueMap = rulesByScopeAndValue.get(scope);
        if (valueMap == null) {
            return Collections.emptyList();
        }
        List<Rule> rules = valueMap.get(scopeValue);
        return rules != null ? new ArrayList<>(rules) : Collections.emptyList();
    }

    public List<Rule> getAllSchoolRules() {
        return rulesById.values().stream()
            .filter(r -> "School".equals(r.scope))
            .collect(Collectors.toList());
    }

    public Profile getProfile(Long id) {
        return profilesById.get(id);
    }

    // Helper methods to remove items from indexes
    private void removeSessionFromIndexes(Long sessionId, Session session) {
        // Remove from email index
        if (session.studentEmail != null) {
            sessionsByEmail.computeIfPresent(session.studentEmail, (email, sessions) -> {
                sessions.removeIf(s -> s.id.equals(sessionId));
                return sessions.isEmpty() ? null : sessions;
            });
        }

        // Remove from profile index
        if (session.profileId != null) {
            sessionsByProfile.computeIfPresent(session.profileId, (profileId, sessions) -> {
                sessions.removeIf(s -> s.id.equals(sessionId));
                return sessions.isEmpty() ? null : sessions;
            });
        }
    }

    private void removeRuleFromIndexes(Long ruleId, Rule rule) {
        // Remove from scope index
        if (rule.scope != null && rule.scopeValue != null) {
            rulesByScopeAndValue.computeIfPresent(rule.scope, (scope, valueMap) -> {
                valueMap.computeIfPresent(rule.scopeValue, (value, rules) -> {
                    rules.removeIf(r -> r.id.equals(ruleId));
                    return rules.isEmpty() ? null : rules;
                });
                return valueMap.isEmpty() ? null : valueMap;
            });
        }
    }

    // Scheduled cleanup job to remove stale data
    @io.quarkus.scheduler.Scheduled(every = "6h", delay = 1, delayUnit = java.util.concurrent.TimeUnit.HOURS)
    void cleanupStaleData() {
        LOG.info("🧹 Starting scheduled cleanup of stale data...");

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // Clean up old sessions (keep only today's sessions)
        int removedSessions = 0;
        Iterator<Map.Entry<Long, Session>> sessionIterator = sessionsById.entrySet().iterator();
        while (sessionIterator.hasNext()) {
            Map.Entry<Long, Session> entry = sessionIterator.next();
            Session session = entry.getValue();

            if (session.startTime != null && session.startTime.toLocalDate().isBefore(today)) {
                removeSessionFromIndexes(entry.getKey(), session);
                sessionIterator.remove();
                removedSessions++;
            }
        }

        // Clean up expired rules
        int removedRules = 0;
        Iterator<Map.Entry<Long, Rule>> ruleIterator = rulesById.entrySet().iterator();
        while (ruleIterator.hasNext()) {
            Map.Entry<Long, Rule> entry = ruleIterator.next();
            Rule rule = entry.getValue();

            if (rule.endTime != null && rule.endTime.isBefore(now)) {
                removeRuleFromIndexes(entry.getKey(), rule);
                ruleIterator.remove();
                removedRules++;
            }
        }

        LOG.info("✅ Cleanup completed: removed " + removedSessions + " old sessions and " + removedRules + " expired rules");
    }

    /**
     * Get all active profiles for a student by their email.
     * This includes both session-based profiles and rule-based profiles.
     *
     * @param studentEmail The student's email
     * @return Set of active profile IDs
     */
    public Set<Long> getActiveProfilesForStudent(String studentEmail) {
        Set<Long> activeProfiles = new HashSet<>();

        // Get student's sessions
        List<Session> sessions = getSessionsByEmail(studentEmail);
        if (sessions.isEmpty()) {
            return activeProfiles;
        }

        // Use first session to get student attributes for rule matching
        Session anySession = sessions.get(0);
        Long studentId = anySession.studentId;
        Long schoolId = anySession.schoolId;
        Integer grade = anySession.grade;
        Long classId = anySession.classId;

        // 1. Check session-based profiles
        for (Session session : sessions) {
            if (session.isActiveNow() && session.profileId != null) {
                activeProfiles.add(session.profileId);
            }
        }

        // 2. Check rule-based profiles (O(1) lookups!)
        // Direct student rule
        if (studentId != null) {
            List<Rule> studentRules = getRulesByScope("Student", String.valueOf(studentId));
            for (Rule rule : studentRules) {
                if (rule.isActiveNow() && rule.profileId != null) {
                    activeProfiles.add(rule.profileId);
                }
            }
        }

        // School-wide rule
        if (schoolId != null) {
            List<Rule> schoolRules = getRulesByScope("School", String.valueOf(schoolId));
            for (Rule rule : schoolRules) {
                if (rule.isActiveNow() && rule.profileId != null) {
                    activeProfiles.add(rule.profileId);
                }
            }
        }

        // Grade-level rule
        if (grade != null) {
            List<Rule> gradeRules = getRulesByScope("Grade", String.valueOf(grade));
            for (Rule rule : gradeRules) {
                if (rule.isActiveNow() && rule.profileId != null) {
                    activeProfiles.add(rule.profileId);
                }
            }
        }

        // Class-specific rule
        if (classId != null) {
            List<Rule> classRules = getRulesByScope("Class", String.valueOf(classId));
            for (Rule rule : classRules) {
                if (rule.isActiveNow() && rule.profileId != null) {
                    activeProfiles.add(rule.profileId);
                }
            }
        }

        return activeProfiles;
    }

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("studentsById", studentsById.size());
        stats.put("profilesById", profilesById.size());
        stats.put("rulesById", rulesById.size());
        stats.put("sessionsById", sessionsById.size());
        stats.put("sessionsByEmail", sessionsByEmail.size());
        stats.put("sessionsByProfile", sessionsByProfile.size());
        stats.put("rulesByScopeAndValue", rulesByScopeAndValue.size());
        return stats;
    }
}
