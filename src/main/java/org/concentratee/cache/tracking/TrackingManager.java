package org.concentratee.cache.tracking;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.concentratee.cache.CacheManager;
import org.concentratee.cache.model.Rule;
import org.concentratee.cache.model.Session;
import org.concentratee.cache.model.Student;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages all appearance tracking for sessions and rules.
 * Provides indexed access to avoid full table scans.
 * Thread-safe with concurrent data structures.
 */
@ApplicationScoped
public class TrackingManager {

    private static final Logger LOG = Logger.getLogger(TrackingManager.class);
    private static final int STALE_THRESHOLD_MINUTES = 30;

    @Inject
    CacheManager cacheManager;

    @Inject
    PgPool pgPool;

    // Primary trackers
    private final Map<Long, SessionTracker> sessionTrackers = new ConcurrentHashMap<>();
    private final Map<String, RuleTracker> ruleTrackers = new ConcurrentHashMap<>();

    // Indexes for efficient lookups (avoid full table scans)
    private final Map<String, Set<Long>> studentEmailToSessionIds = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> teacherIdToSessionIds = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> schoolIdToRuleContextKeys = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        LOG.info("TrackingManager initialized - ready to track appearance");
    }

    /**
     * Record a heartbeat for a student in their active sessions and rules.
     * This is the main entry point called from the API.
     */
    public void recordHeartbeat(String studentEmail) {
        LocalDateTime now = LocalDateTime.now();

        // Get student from cache
        Student student = cacheManager.getStudentByEmail(studentEmail);
        if (student == null) {
            LOG.debugf("Student not found: %s", studentEmail);
            return;
        }

        // 1. Record for active sessions
        Set<Session> activeSessions = cacheManager.getSessionsByEmail(studentEmail).stream()
            .filter(s -> s.isActiveNow())
            .collect(Collectors.toSet());

        activeSessions.forEach(session -> {
            SessionTracker tracker = sessionTrackers.computeIfAbsent(session.id, id -> {
                LOG.infof("Creating tracker for session %d", session.id);
                SessionTracker newTracker = new SessionTracker(session);
                indexSession(session);
                return newTracker;
            });
            tracker.recordHeartbeat(studentEmail);
        });

        // 2. Record for active rules (without sessions)
        Set<Rule> activeRules = cacheManager.getActiveRulesForStudent(student, now);

        // Filter rules that don't have corresponding active sessions
        activeRules = activeRules.stream()
            .filter(rule -> {
                // Check if student has active session with this rule's profile
                boolean hasSessionWithProfile = activeSessions.stream()
                    .anyMatch(s -> s.profileId != null && s.profileId.equals(rule.profileId));
                return !hasSessionWithProfile;  // Only track if NO session exists
            })
            .collect(Collectors.toSet());

        activeRules.forEach(rule -> {
            String contextKey = RuleTracker.buildContextKey(rule.scope, rule.scopeValue, student.schoolId);
            RuleTracker tracker = ruleTrackers.computeIfAbsent(contextKey, key -> {
                LOG.infof("Creating tracker for rule context %s", contextKey);
                RuleTracker newTracker = new RuleTracker(rule, student.schoolId);
                indexRuleContext(student.schoolId, contextKey);
                return newTracker;
            });
            tracker.recordHeartbeat(studentEmail);
        });

        LOG.debugf("Recorded heartbeat for %s: %d sessions, %d rule contexts",
            studentEmail, activeSessions.size(), activeRules.size());
    }

    /**
     * Get tracking data for a specific session.
     */
    public Map<String, AttendanceStats> getSessionTracking(Long sessionId) {
        SessionTracker tracker = sessionTrackers.get(sessionId);
        if (tracker == null) {
            return Collections.emptyMap();
        }
        return tracker.calculateStats();
    }

    /**
     * Get tracking data for a specific rule context.
     */
    public Map<String, AttendanceStats> getRuleTracking(String ruleContextKey) {
        RuleTracker tracker = ruleTrackers.get(ruleContextKey);
        if (tracker == null) {
            return Collections.emptyMap();
        }
        return tracker.calculateStats();
    }

    /**
     * Get all sessions tracked for a specific teacher.
     * Uses index to avoid full table scan.
     */
    public Map<Long, Map<String, AttendanceStats>> getTeacherSessionTracking(Long teacherId) {
        Set<Long> sessionIds = teacherIdToSessionIds.getOrDefault(teacherId, Collections.emptySet());
        Map<Long, Map<String, AttendanceStats>> result = new HashMap<>();

        sessionIds.forEach(sessionId -> {
            SessionTracker tracker = sessionTrackers.get(sessionId);
            if (tracker != null) {
                result.put(sessionId, tracker.calculateStats());
            }
        });

        return result;
    }

    /**
     * Get all rule contexts for a specific school.
     * Uses index to avoid full table scan.
     */
    public Map<String, Map<String, AttendanceStats>> getSchoolRuleTracking(Long schoolId) {
        Set<String> contextKeys = schoolIdToRuleContextKeys.getOrDefault(schoolId, Collections.emptySet());
        Map<String, Map<String, AttendanceStats>> result = new HashMap<>();

        contextKeys.forEach(contextKey -> {
            RuleTracker tracker = ruleTrackers.get(contextKey);
            if (tracker != null) {
                result.put(contextKey, tracker.calculateStats());
            }
        });

        return result;
    }

    /**
     * Rotate all trackers to next minute.
     * Scheduled to run every minute at :00 seconds.
     */
    @Scheduled(cron = "0 * * * * ?")
    void rotateMinute() {
        LOG.debug("Rotating minute for all trackers");
        sessionTrackers.values().forEach(SessionTracker::rotateMinute);
        ruleTrackers.values().forEach(RuleTracker::rotateMinute);
    }

    /**
     * Cleanup ended sessions.
     * Runs every 5 minutes.
     */
    @Scheduled(cron = "0 */5 * * * ?")
    void cleanupEndedSessions() {
        LOG.debug("Cleaning up ended sessions");

        List<Long> endedSessionIds = sessionTrackers.entrySet().stream()
            .filter(e -> e.getValue().hasEnded())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        endedSessionIds.forEach(sessionId -> {
            SessionTracker tracker = sessionTrackers.get(sessionId);
            persistSessionTracking(sessionId, tracker);
            sessionTrackers.remove(sessionId);
            removeSessionFromIndexes(sessionId);
            LOG.infof("Cleaned up session %d", sessionId);
        });

        if (!endedSessionIds.isEmpty()) {
            LOG.infof("Cleaned up %d ended sessions", endedSessionIds.size());
        }
    }

    /**
     * Cleanup stale rule contexts.
     * Runs every 10 minutes.
     */
    @Scheduled(cron = "0 */10 * * * ?")
    void cleanupStaleRuleContexts() {
        LOG.debug("Cleaning up stale rule contexts");

        List<String> staleContextKeys = ruleTrackers.entrySet().stream()
            .filter(e -> e.getValue().isStale(STALE_THRESHOLD_MINUTES))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        staleContextKeys.forEach(contextKey -> {
            RuleTracker tracker = ruleTrackers.get(contextKey);
            ruleTrackers.remove(contextKey);
            removeRuleContextFromIndexes(contextKey, tracker.getSchoolId());
            LOG.infof("Cleaned up stale rule context %s", contextKey);
        });

        if (!staleContextKeys.isEmpty()) {
            LOG.infof("Cleaned up %d stale rule contexts", staleContextKeys.size());
        }
    }

    /**
     * Persist session tracking data to database.
     * NOTE: is_active and percentage columns are per-session aggregates, not per-student.
     * We calculate the overall session attendance percentage.
     */
    private void persistSessionTracking(Long sessionId, SessionTracker tracker) {
        Map<String, AttendanceStats> stats = tracker.calculateStats();

        if (stats.isEmpty()) {
            return; // No students tracked, nothing to persist
        }

        // Calculate aggregate: average percentage across all students
        double totalPercentage = stats.values().stream()
            .mapToDouble(s -> s.percentage)
            .average()
            .orElse(0.0);

        // Is session active? Use 80% threshold of students being active
        long activeStudents = stats.values().stream()
            .filter(s -> s.isActive)
            .count();
        boolean sessionIsActive = (activeStudents / (double) stats.size()) > 0.8;

        // Persist aggregate values
        String query = String.format(
            "UPDATE sessions SET is_active = %s, percentage = %s WHERE id = %d",
            sessionIsActive, totalPercentage, sessionId
        );

        pgPool.query(query).execute().subscribe().with(
            success -> LOG.debug("Persisted tracking for session " + sessionId +
                ": avg=" + String.format("%.2f", totalPercentage) + "%, active=" + sessionIsActive),
            failure -> LOG.errorf("Failed to persist tracking: %s", failure.getMessage())
        );
    }

    /**
     * Index a session for efficient lookups.
     */
    private void indexSession(Session session) {
        // Index by student email
        if (session.studentEmail != null) {
            studentEmailToSessionIds
                .computeIfAbsent(session.studentEmail, k -> ConcurrentHashMap.newKeySet())
                .add(session.id);
        }

        // Index by teacher ID
        if (session.teacherId != null) {
            teacherIdToSessionIds
                .computeIfAbsent(session.teacherId, k -> ConcurrentHashMap.newKeySet())
                .add(session.id);
        }
    }

    /**
     * Index a rule context for efficient lookups.
     */
    private void indexRuleContext(Long schoolId, String contextKey) {
        schoolIdToRuleContextKeys
            .computeIfAbsent(schoolId, k -> ConcurrentHashMap.newKeySet())
            .add(contextKey);
    }

    /**
     * Remove session from all indexes.
     */
    private void removeSessionFromIndexes(Long sessionId) {
        // Remove from student email index
        studentEmailToSessionIds.values().forEach(set -> set.remove(sessionId));

        // Remove from teacher ID index
        teacherIdToSessionIds.values().forEach(set -> set.remove(sessionId));
    }

    /**
     * Remove rule context from all indexes.
     */
    private void removeRuleContextFromIndexes(String contextKey, Long schoolId) {
        Set<String> schoolContexts = schoolIdToRuleContextKeys.get(schoolId);
        if (schoolContexts != null) {
            schoolContexts.remove(contextKey);
        }
    }

    // Public accessors for testing and stats

    public int getActiveSessionTrackerCount() {
        return sessionTrackers.size();
    }

    public int getActiveRuleTrackerCount() {
        return ruleTrackers.size();
    }

    public Map<String, Object> getStats() {
        return Map.of(
            "activeSessions", sessionTrackers.size(),
            "activeRuleContexts", ruleTrackers.size(),
            "indexedStudents", studentEmailToSessionIds.size(),
            "indexedTeachers", teacherIdToSessionIds.size(),
            "indexedSchools", schoolIdToRuleContextKeys.size()
        );
    }

    // For testing
    public Map<Long, SessionTracker> getSessionTrackers() {
        return new HashMap<>(sessionTrackers);
    }

    public Map<String, RuleTracker> getRuleTrackers() {
        return new HashMap<>(ruleTrackers);
    }
}
