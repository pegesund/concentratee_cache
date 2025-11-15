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
import org.concentratee.cache.model.CategoryUrl;
import org.concentratee.cache.model.Profile;
import org.concentratee.cache.model.Rule;
import org.concentratee.cache.model.Session;
import org.concentratee.cache.model.Student;
import org.concentratee.cache.model.UrlCategory;
import org.concentratee.cache.model.UrlSubcategory;
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
    private final ConcurrentHashMap<Long, Student> studentsById = new ConcurrentHashMap<>(); // studentId -> Student

    // Derived indexes for fast lookups
    private final ConcurrentHashMap<String, Student> studentsByEmail = new ConcurrentHashMap<>(); // email -> Student
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
            SELECT id, feide_email, school_id, class_id
            FROM students
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                int count = 0;
                for (Row row : rows) {
                    Long id = row.getLong("id");
                    String email = row.getString("feide_email");
                    Long schoolId = row.getLong("school_id");
                    Long classId = row.getLong("class_id");

                    if (email != null) {
                        Student student = new Student(id, email, schoolId);
                        student.classId = classId;
                        // Note: grade is not stored in students table, only in sessions
                        student.grade = null;

                        studentsById.put(id, student);
                        studentsByEmail.put(email, student);
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
                    Profile profile = new Profile();  // Constructor initializes lists
                    profile.id = row.getLong("id");
                    profile.name = row.getString("name");
                    profile.teacherId = row.getLong("teacher_id");
                    profile.schoolId = row.getLong("school_id");
                    profile.isWhitelistUrl = row.getBoolean("is_whitelist_url");

                    // TODO: Load from database once tracking_enabled column is added
                    // For now, all profiles have tracking enabled by default
                    profile.trackingEnabled = true;

                    // Parse JSONB domains array
                    String domainsJson = row.get(Object.class, "domains").toString();
                    profile.domains = parseDomainsFromJson(domainsJson);

                    profilesById.put(profile.id, profile);
                    count++;
                }
                LOG.debug("Loaded " + count + " profiles");
                return null;
            })
            .chain(() -> loadPrograms())
            .chain(() -> loadCategories());
    }

    private Uni<Void> loadPrograms() {
        String sql = """
            SELECT pp.profile_id, pr.name
            FROM profiles_programs pp
            JOIN programs pr ON pp.program_id = pr.id
            ORDER BY pp.profile_id, pr.name
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                int count = 0;
                for (Row row : rows) {
                    Long profileId = row.getLong("profile_id");
                    String programName = row.getString("name");

                    Profile profile = profilesById.get(profileId);
                    if (profile != null) {
                        profile.programs.add(programName);
                        count++;
                    }
                }
                LOG.debug("Loaded " + count + " program associations");
                return null;
            });
    }

    private Uni<Void> loadCategories() {
        // First load all active categories for each profile
        // Key insight: we need to track profiles_categories.id to look up inactive items
        String categoriesSql = """
            SELECT pc.id as pc_id, pc.profile_id, pc.url_category_id, pc.is_active, uc.name as category_name
            FROM profiles_categories pc
            JOIN url_categories uc ON pc.url_category_id = uc.id
            WHERE pc.is_active = true
            ORDER BY pc.profile_id, uc.name
            """;

        return client.query(categoriesSql).execute()
            .onItem().transformToUni(categoryRows -> {
                // Build category objects - key is profiles_categories.id
                Map<Long, UrlCategory> categoryCache = new ConcurrentHashMap<>();
                Map<Long, Long> profileCategoryIdToProfileId = new ConcurrentHashMap<>();

                for (Row row : categoryRows) {
                    Long profileCategoryId = row.getLong("pc_id");
                    Long profileId = row.getLong("profile_id");
                    Long categoryId = row.getLong("url_category_id");
                    String categoryName = row.getString("category_name");

                    Profile profile = profilesById.get(profileId);
                    if (profile != null) {
                        UrlCategory category = new UrlCategory();
                        category.id = categoryId;
                        category.name = categoryName;

                        categoryCache.put(profileCategoryId, category);
                        profileCategoryIdToProfileId.put(profileCategoryId, profileId);
                        profile.categories.add(category);
                    }
                }

                LOG.debug("Loaded " + categoryCache.size() + " active categories");

                // Now load subcategories
                return loadSubcategories(categoryCache, profileCategoryIdToProfileId);
            });
    }

    private Uni<Void> loadSubcategories(Map<Long, UrlCategory> categoryCache,
                                        Map<Long, Long> profileCategoryIdToProfileId) {
        // Load all subcategories for the active categories
        String subcategoriesSql = """
            SELECT us.id, us.name, us.url_category_id
            FROM url_subcategories us
            """;

        return client.query(subcategoriesSql).execute()
            .onItem().transformToUni(subcatRows -> {
                // Get inactive subcategories for filtering
                String inactiveSql = """
                    SELECT profiles_category_id, url_subcategory_id
                    FROM profile_inactive_subcategories
                    """;

                return client.query(inactiveSql).execute()
                    .onItem().transformToUni(inactiveRows -> {
                        // Build set of inactive subcategory keys: profiles_category_id + subcategory_id
                        Set<String> inactiveSubcats = new HashSet<>();
                        for (Row row : inactiveRows) {
                            Long profileCategoryId = row.getLong("profiles_category_id");
                            Long subcatId = row.getLong("url_subcategory_id");
                            inactiveSubcats.add(profileCategoryId + ":" + subcatId);
                        }

                        // Build subcategory objects and attach to categories
                        // Key is profiles_category_id + subcategory_id
                        Map<String, UrlSubcategory> subcategoryCache = new ConcurrentHashMap<>();
                        int count = 0;

                        for (Map.Entry<Long, UrlCategory> entry : categoryCache.entrySet()) {
                            Long profileCategoryId = entry.getKey();
                            UrlCategory category = entry.getValue();
                            Long categoryId = category.id;

                            for (Row row : subcatRows) {
                                Long subcatCategoryId = row.getLong("url_category_id");
                                if (!subcatCategoryId.equals(categoryId)) {
                                    continue;
                                }

                                Long subcatId = row.getLong("id");
                                String subcatKey = profileCategoryId + ":" + subcatId;

                                // Skip if inactive for this profile_category
                                if (inactiveSubcats.contains(subcatKey)) {
                                    continue;
                                }

                                UrlSubcategory subcat = new UrlSubcategory();
                                subcat.id = subcatId;
                                subcat.name = row.getString("name");

                                category.subcategories.add(subcat);
                                subcategoryCache.put(subcatKey, subcat);
                                count++;
                            }
                        }

                        LOG.debug("Loaded " + count + " active subcategories");

                        // Now load URLs
                        return loadCategoryUrls(subcategoryCache);
                    });
            });
    }

    private Uni<Void> loadCategoryUrls(Map<String, UrlSubcategory> subcategoryCache) {
        // Load all URLs
        String urlsSql = """
            SELECT id, url, subcategory_id
            FROM urls
            """;

        return client.query(urlsSql).execute()
            .onItem().transformToUni(urlRows -> {
                // Get inactive URLs for filtering
                String inactiveUrlsSql = """
                    SELECT profiles_category_id, url_id
                    FROM profile_inactive_urls
                    """;

                return client.query(inactiveUrlsSql).execute()
                    .onItem().transform(inactiveRows -> {
                        // Build set of inactive URL keys: profiles_category_id + url_id
                        Set<String> inactiveUrls = new HashSet<>();
                        for (Row row : inactiveRows) {
                            Long profileCategoryId = row.getLong("profiles_category_id");
                            Long urlId = row.getLong("url_id");
                            inactiveUrls.add(profileCategoryId + ":" + urlId);
                        }

                        // Attach URLs to subcategories
                        int count = 0;

                        for (Map.Entry<String, UrlSubcategory> entry : subcategoryCache.entrySet()) {
                            String key = entry.getKey(); // "profileCategoryId:subcatId"
                            String[] parts = key.split(":");
                            Long profileCategoryId = Long.parseLong(parts[0]);
                            Long subcatId = Long.parseLong(parts[1]);
                            UrlSubcategory subcat = entry.getValue();

                            for (Row row : urlRows) {
                                Long urlSubcatId = row.getLong("subcategory_id");
                                if (!urlSubcatId.equals(subcatId)) {
                                    continue;
                                }

                                Long urlId = row.getLong("id");
                                String urlKey = profileCategoryId + ":" + urlId;

                                // Skip if inactive for this profile_category
                                if (inactiveUrls.contains(urlKey)) {
                                    continue;
                                }

                                CategoryUrl categoryUrl = new CategoryUrl();
                                categoryUrl.id = urlId;
                                categoryUrl.url = row.getString("url");

                                subcat.urls.add(categoryUrl);
                                count++;
                            }
                        }

                        LOG.debug("Loaded " + count + " active URLs");
                        return null;
                    });
            });
    }

    private Uni<Void> loadRules() {
        String sql = """
            SELECT id, scope, scope_value, start_time, end_time, profile_id
            FROM rules
            WHERE end_time >= CURRENT_TIMESTAMP
              AND start_time <= CURRENT_TIMESTAMP + INTERVAL '7 days'
            """;

        return client.query(sql).execute()
            .onItem().transform(rows -> {
                int count = 0;
                for (Row row : rows) {
                    Rule rule = new Rule();
                    rule.id = row.getLong("id");
                    rule.scope = row.getString("scope");
                    // Normalize NULL scope_value to empty string for consistent wildcard handling
                    String scopeValue = row.getString("scope_value");
                    rule.scopeValue = (scopeValue != null) ? scopeValue : "";
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
            WHERE DATE(start_time) >= CURRENT_DATE
              AND DATE(start_time) <= CURRENT_DATE + INTERVAL '7 days'
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
                    Student student = studentsById.get(session.studentId);
                    session.studentEmail = student != null ? student.email : null;

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
                // Index all rules that have a scope, including wildcard rules (empty scope_value)
                if (rule.scope != null) {
                    String scopeValue = (rule.scopeValue != null) ? rule.scopeValue : "";
                    tempMap.computeIfAbsent(rule.scope, k -> new HashMap<>())
                           .computeIfAbsent(scopeValue, k -> new ArrayList<>())
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

        // Connect and setup listeners - BLOCKING to ensure connection is established
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

                LOG.info("✅ LISTEN/NOTIFY setup complete - listening on 4 channels");
            } else {
                LOG.error("❌ Failed to setup LISTEN/NOTIFY: " + ar.cause().getMessage(), ar.cause());
            }
        });

        // Wait a bit to ensure connection completes
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        if (subscriber != null) {
            subscriber.close();
            LOG.info("🔌 Closed PostgreSQL LISTEN/NOTIFY connection");
        }
    }

    /**
     * Wait for any pending database notifications to be processed.
     * This is useful in tests where LISTEN/NOTIFY is asynchronous.
     *
     * @param maxWaitMillis Maximum time to wait in milliseconds
     */
    public void waitForNotifications(long maxWaitMillis) {
        try {
            Thread.sleep(maxWaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check if the LISTEN/NOTIFY subscriber is connected.
     * Useful for tests to verify the connection is alive.
     */
    public boolean isSubscriberConnected() {
        boolean connected = subscriber != null;
        LOG.info("🔍 Checking subscriber connection: " + (connected ? "CONNECTED" : "NOT CONNECTED"));
        return connected;
    }

    private void handleProfileChange(String payload) {
        // Parse JSON payload and update cache
        // For now, just reload the profile
        try {
            // Simple parsing - handle payloads with or without spaces
            if (payload.contains("DELETE")) {
                Long id = extractId(payload);
                profilesById.remove(id);
                LOG.info("  ➡️ Removed profile " + id + " from cache");
            } else if (payload.contains("RELOAD_ALL")) {
                // URL hierarchy changed (categories, subcategories, urls)
                String triggerTable = extractTriggerTable(payload);
                LOG.info("  ➡️ Reloading ALL profiles due to change in " + triggerTable);
                loadProfiles().subscribe().with(
                    v -> LOG.info("  ✅ Reloaded all profiles from cache"),
                    failure -> LOG.error("Failed to reload all profiles", failure)
                );
            } else if (payload.contains("RELOAD")) {
                // Profile-related table changed (programs, categories, inactive lists)
                Long id = extractId(payload);
                String triggerTable = extractTriggerTable(payload);
                LOG.info("  ➡️ Reloading profile " + id + " due to change in " + triggerTable);
                loadProfileById(id).subscribe().with(
                    v -> LOG.info("  ✅ Reloaded profile " + id + " from cache"),
                    failure -> LOG.error("Failed to reload profile " + id, failure)
                );
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

    private String extractTriggerTable(String payload) {
        try {
            // Handle both "trigger_table":"value" and "trigger_table" : "value"
            int keyIndex = payload.indexOf("\"trigger_table\"");
            if (keyIndex == -1) return "unknown";

            int firstQuote = payload.indexOf("\"", keyIndex + 15);
            if (firstQuote == -1) return "unknown";

            int start = firstQuote + 1;
            int end = payload.indexOf("\"", start);
            if (end == -1) return "unknown";

            return payload.substring(start, end);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void handleRuleChange(String payload) {
        try {
            if (payload.contains("\"operation\":\"DELETE\"")) {
                Long id = extractId(payload);
                Rule rule = rulesById.remove(id);
                if (rule != null && rule.scope != null) {
                    // Remove from scope index (scopeValue might be empty string for wildcards)
                    String scopeValue = (rule.scopeValue != null) ? rule.scopeValue : "";
                    rulesByScopeAndValue.computeIfPresent(rule.scope, (scope, valueMap) -> {
                        valueMap.computeIfPresent(scopeValue, (value, rules) -> {
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
                Student oldStudent = studentsById.remove(id);
                if (oldStudent != null) {
                    // Remove from email index
                    studentsByEmail.remove(oldStudent.email);
                    // Remove all sessions for this student from sessionsByEmail
                    sessionsByEmail.remove(oldStudent.email);
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
        // Load basic profile data
        String sql = """
            SELECT p.id, p.name, p.domains, p.teacher_id, p.school_id, p.is_whitelist_url
            FROM profiles p
            WHERE p.id = $1
            """;

        return client.preparedQuery(sql).execute(io.vertx.mutiny.sqlclient.Tuple.of(id))
            .onItem().transformToUni(rows -> {
                Profile profile = null;
                for (Row row : rows) {
                    // Reuse existing profile if it exists to avoid race conditions
                    profile = profilesById.get(id);
                    if (profile == null) {
                        profile = new Profile();
                        profilesById.put(id, profile);
                    }

                    // Update profile fields
                    profile.id = row.getLong("id");
                    profile.name = row.getString("name");
                    profile.teacherId = row.getLong("teacher_id");
                    profile.schoolId = row.getLong("school_id");
                    profile.isWhitelistUrl = row.getBoolean("is_whitelist_url");
                    String domainsJson = row.get(Object.class, "domains").toString();
                    profile.domains = parseDomainsFromJson(domainsJson);
                }

                // Load programs and categories BEFORE completing
                // This ensures the profile is fully loaded when we return
                return loadProgramsForProfile(id);
            })
            .chain(() -> loadCategoriesForProfile(id));
    }

    private Uni<Void> loadProgramsForProfile(Long profileId) {
        String sql = """
            SELECT pr.name
            FROM profiles_programs pp
            JOIN programs pr ON pp.program_id = pr.id
            WHERE pp.profile_id = $1
            ORDER BY pr.name
            """;

        return client.preparedQuery(sql).execute(io.vertx.mutiny.sqlclient.Tuple.of(profileId))
            .onItem().transform(rows -> {
                Profile profile = profilesById.get(profileId);
                if (profile != null) {
                    profile.programs.clear(); // Clear existing programs
                    int count = 0;
                    for (Row row : rows) {
                        profile.programs.add(row.getString("name"));
                        count++;
                    }
                    LOG.debug("Loaded " + count + " programs for profile " + profileId);
                }
                return null;
            });
    }

    private Uni<Void> loadCategoriesForProfile(Long profileId) {
        // Load active categories for this specific profile
        String categoriesSql = """
            SELECT pc.id as pc_id, pc.url_category_id, uc.name as category_name
            FROM profiles_categories pc
            JOIN url_categories uc ON pc.url_category_id = uc.id
            WHERE pc.profile_id = $1 AND pc.is_active = true
            ORDER BY uc.name
            """;

        return client.preparedQuery(categoriesSql).execute(io.vertx.mutiny.sqlclient.Tuple.of(profileId))
            .onItem().transformToUni(categoryRows -> {
                Profile profile = profilesById.get(profileId);
                if (profile == null) {
                    return Uni.createFrom().voidItem();
                }

                profile.categories.clear(); // Clear existing categories
                Map<Long, UrlCategory> categoryCache = new ConcurrentHashMap<>();
                Map<Long, Long> profileCategoryIdToProfileId = new ConcurrentHashMap<>();

                for (Row row : categoryRows) {
                    Long profileCategoryId = row.getLong("pc_id");
                    Long categoryId = row.getLong("url_category_id");
                    String categoryName = row.getString("category_name");

                    UrlCategory category = new UrlCategory();
                    category.id = categoryId;
                    category.name = categoryName;

                    categoryCache.put(profileCategoryId, category);
                    profileCategoryIdToProfileId.put(profileCategoryId, profileId);
                    profile.categories.add(category);
                }

                // If no categories, return early
                if (categoryCache.isEmpty()) {
                    return Uni.createFrom().voidItem();
                }

                // Load subcategories for these categories
                return loadSubcategories(categoryCache, profileCategoryIdToProfileId);
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
                if (oldRule != null && oldRule.scope != null) {
                    // Normalize scopeValue for removal (might be empty string for wildcards)
                    String oldScopeValue = (oldRule.scopeValue != null) ? oldRule.scopeValue : "";
                    rulesByScopeAndValue.computeIfPresent(oldRule.scope, (scope, valueMap) -> {
                        valueMap.computeIfPresent(oldScopeValue, (value, rules) -> {
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
                    // Normalize NULL scope_value to empty string for consistent wildcard handling
                    String scopeValue = row.getString("scope_value");
                    rule.scopeValue = (scopeValue != null) ? scopeValue : "";
                    rule.startTime = row.getLocalDateTime("start_time");
                    rule.endTime = row.getLocalDateTime("end_time");
                    rule.profileId = row.getLong("profile_id");
                    rulesById.put(rule.id, rule);

                    // Add to scope index (including wildcard rules with empty scope_value)
                    if (rule.scope != null) {
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
                    Student student = studentsById.get(session.studentId);
                    session.studentEmail = student != null ? student.email : null;

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
            SELECT id, feide_email, school_id, class_id
            FROM students
            WHERE id = $1
            """;

        return client.preparedQuery(sql).execute(io.vertx.mutiny.sqlclient.Tuple.of(id))
            .onItem().transform(rows -> {
                Student oldStudent = studentsById.get(id);
                String oldEmail = oldStudent != null ? oldStudent.email : null;

                for (Row row : rows) {
                    Long studentId = row.getLong("id");
                    String newEmail = row.getString("feide_email");
                    Long schoolId = row.getLong("school_id");
                    Long classId = row.getLong("class_id");

                    // Create and store new Student object
                    Student student = new Student(studentId, newEmail, schoolId);
                    student.classId = classId;
                    student.grade = null; // grade not in students table

                    studentsById.put(studentId, student);

                    // Update email index
                    if (oldEmail != null) {
                        studentsByEmail.remove(oldEmail);
                    }
                    if (newEmail != null) {
                        studentsByEmail.put(newEmail, student);
                    }

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

        List<String> domains = new ArrayList<>();

        // Parse JSONB array of objects: [{"id": "uuid", "name": "domain"}, ...]
        // Extract only the "name" field from each object
        // Simple regex-based parsing - matches "name": "value"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);

        while (matcher.find()) {
            String domainName = matcher.group(1);
            if (domainName != null && !domainName.isEmpty()) {
                domains.add(domainName);
            }
        }

        return domains;
    }

    // Public getter methods for accessing cached data
    public List<Session> getSessionsByEmail(String email) {
        List<Session> sessions = sessionsByEmail.get(email);
        if (sessions == null) {
            return Collections.emptyList();
        }

        // Return cached sessions directly - already filtered at load time (today + 7 days)
        // The caller (getActiveProfilesForStudent) filters by isActiveNow() for active sessions
        return sessions;
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

    public Rule getRule(Long id) {
        return rulesById.get(id);
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

    // Scheduled job at midnight to refresh cache for new day
    @io.quarkus.scheduler.Scheduled(cron = "0 0 0 * * ?", identity = "midnight-refresh")
    void midnightCacheRefresh() {
        LOG.info("🌙 Midnight cache refresh starting - loading tomorrow's sessions and rules...");

        // Reload sessions and rules to pick up tomorrow's data
        loadSessions().subscribe().with(
            v -> LOG.info("✅ Midnight refresh: Sessions reloaded"),
            failure -> LOG.error("❌ Midnight refresh: Failed to reload sessions", failure)
        );

        loadRules().subscribe().with(
            v -> LOG.info("✅ Midnight refresh: Rules reloaded"),
            failure -> LOG.error("❌ Midnight refresh: Failed to reload rules", failure)
        );

        LOG.info("🌅 Midnight cache refresh completed");
    }

    // Scheduled cleanup job to remove stale data
    @io.quarkus.scheduler.Scheduled(every = "6h", delay = 1, delayUnit = java.util.concurrent.TimeUnit.HOURS)
    void cleanupStaleData() {
        LOG.info("🧹 Starting scheduled cleanup of stale data...");

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // Clean up expired sessions (sessions that have ended, not sessions that started before today)
        int removedSessions = 0;
        Iterator<Map.Entry<Long, Session>> sessionIterator = sessionsById.entrySet().iterator();
        while (sessionIterator.hasNext()) {
            Map.Entry<Long, Session> entry = sessionIterator.next();
            Session session = entry.getValue();

            // Remove sessions that have actually ended (expired), not sessions that started in the past
            // This allows year-long sessions to remain in cache as long as they are still active
            if (session.endTime != null && session.endTime.isBefore(now)) {
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

        // Get student attributes for rule matching
        // First try to get from studentsByEmail cache (has school_id, class_id from students table)
        Student student = studentsByEmail.get(studentEmail);
        Long studentId = student != null ? student.id : null;
        Long schoolId = student != null ? student.schoolId : null;
        Integer grade = null; // grade only available from sessions
        Long classId = student != null ? student.classId : null;

        // If we have sessions, also extract grade (which is only in sessions, not students table)
        if (!sessions.isEmpty()) {
            Session anySession = sessions.get(0);
            if (studentId == null) studentId = anySession.studentId;
            if (schoolId == null) schoolId = anySession.schoolId;
            if (classId == null) classId = anySession.classId;
            grade = anySession.grade;  // grade only comes from sessions
        }

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
            // Check specific school rules
            List<Rule> schoolRules = getRulesByScope("School", String.valueOf(schoolId));
            for (Rule rule : schoolRules) {
                if (rule.isActiveNow() && rule.profileId != null) {
                    activeProfiles.add(rule.profileId);
                }
            }
        }

        // ALWAYS check wildcard school rules (empty scope_value) - they apply to ALL students
        List<Rule> wildcardSchoolRules = getRulesByScope("School", "");
        for (Rule rule : wildcardSchoolRules) {
            if (rule.isActiveNow() && rule.profileId != null) {
                activeProfiles.add(rule.profileId);
            }
        }

        // Grade-level rule
        if (grade != null) {
            // Check specific grade rules
            List<Rule> gradeRules = getRulesByScope("Grade", String.valueOf(grade));
            for (Rule rule : gradeRules) {
                if (rule.isActiveNow() && rule.profileId != null) {
                    activeProfiles.add(rule.profileId);
                }
            }
        }

        // ALWAYS check wildcard grade rules (empty scope_value) - they apply to ALL students
        List<Rule> wildcardGradeRules = getRulesByScope("Grade", "");
        for (Rule rule : wildcardGradeRules) {
            if (rule.isActiveNow() && rule.profileId != null) {
                activeProfiles.add(rule.profileId);
            }
        }

        // Class-specific rule
        if (classId != null) {
            // Check specific class rules
            List<Rule> classRules = getRulesByScope("Class", String.valueOf(classId));
            for (Rule rule : classRules) {
                if (rule.isActiveNow() && rule.profileId != null) {
                    activeProfiles.add(rule.profileId);
                }
            }
        }

        // ALWAYS check wildcard class rules (empty scope_value) - they apply to ALL students
        List<Rule> wildcardClassRules = getRulesByScope("Class", "");
        for (Rule rule : wildcardClassRules) {
            if (rule.isActiveNow() && rule.profileId != null) {
                activeProfiles.add(rule.profileId);
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

    /**
     * Get a Student object by email.
     * Used for tracking purposes.
     */
    public Student getStudentByEmail(String email) {
        // Find student by looking up from sessions
        List<Session> sessions = getSessionsByEmail(email);
        if (sessions.isEmpty()) {
            return null;
        }

        Session session = sessions.get(0);
        return new Student(session.studentId, email, session.schoolId);
    }

    /**
     * Get all active rules for a student at a given time.
     * Filters rules by scope matching student's attributes.
     */
    public Set<Rule> getActiveRulesForStudent(Student student, LocalDateTime now) {
        Set<Rule> activeRules = new HashSet<>();

        // Get sessions for this student to extract grade and class
        List<Session> sessions = getSessionsByEmail(student.email);
        Integer grade = null;
        Long classId = null;

        if (!sessions.isEmpty()) {
            Session session = sessions.get(0);
            grade = session.grade;
            classId = session.classId;
        }

        // Check student-specific rules
        if (student.id != null) {
            List<Rule> studentRules = getRulesByScope("Student", String.valueOf(student.id));
            studentRules.stream()
                .filter(r -> r.isActiveNow(now) && r.profileId != null)
                .forEach(activeRules::add);
        }

        // Check school-wide rules
        if (student.schoolId != null) {
            // Check both specific school rules and wildcard school rules (empty scope_value)
            List<Rule> schoolRules = getRulesByScope("School", String.valueOf(student.schoolId));
            List<Rule> wildcardSchoolRules = getRulesByScope("School", "");

            schoolRules.stream()
                .filter(r -> r.isActiveNow(now) && r.profileId != null)
                .forEach(activeRules::add);
            wildcardSchoolRules.stream()
                .filter(r -> r.isActiveNow(now) && r.profileId != null)
                .forEach(activeRules::add);
        }

        // Check grade-level rules
        if (grade != null) {
            // Check both specific grade rules and wildcard grade rules (empty scope_value)
            List<Rule> gradeRules = getRulesByScope("Grade", String.valueOf(grade));
            List<Rule> wildcardGradeRules = getRulesByScope("Grade", "");

            gradeRules.stream()
                .filter(r -> r.isActiveNow(now) && r.profileId != null)
                .forEach(activeRules::add);
            wildcardGradeRules.stream()
                .filter(r -> r.isActiveNow(now) && r.profileId != null)
                .forEach(activeRules::add);
        }

        // Check class-level rules
        if (classId != null) {
            // Check both specific class rules and wildcard class rules (empty scope_value)
            List<Rule> classRules = getRulesByScope("Class", String.valueOf(classId));
            List<Rule> wildcardClassRules = getRulesByScope("Class", "");

            classRules.stream()
                .filter(r -> r.isActiveNow(now) && r.profileId != null)
                .forEach(activeRules::add);
            wildcardClassRules.stream()
                .filter(r -> r.isActiveNow(now) && r.profileId != null)
                .forEach(activeRules::add);
        }

        return activeRules;
    }
}
