package org.concentratee.cache.tracking;

import org.concentratee.cache.model.Rule;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks appearance/attendance for students in a rule context (without sessions).
 * Thread-safe, minute-level tracking for school/grade/class-wide rules.
 */
public class RuleTracker {

    private final String ruleContextKey;  // "school:123" or "grade:8:school:123"
    private final String scope;           // "School", "Grade", "Class"
    private final String scopeValue;      // "123", "8", "456"
    private final Long schoolId;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final int totalMinutes;
    private final Map<String, StudentMinuteTracker> studentTrackers = new ConcurrentHashMap<>();
    private LocalDateTime lastActivity;

    public RuleTracker(Rule rule, Long schoolId) {
        this.scope = rule.scope;
        this.scopeValue = rule.scopeValue;
        this.schoolId = schoolId;
        this.ruleContextKey = buildContextKey(rule.scope, rule.scopeValue, schoolId);
        this.startTime = rule.startTime;
        this.endTime = rule.endTime;
        this.totalMinutes = (int) ChronoUnit.MINUTES.between(startTime, endTime);
        this.lastActivity = LocalDateTime.now();
    }

    /**
     * Build context key from scope, value, and schoolId.
     * Format: "school:123", "grade:8:school:123", "class:456:school:123"
     */
    public static String buildContextKey(String scope, String scopeValue, Long schoolId) {
        if ("School".equalsIgnoreCase(scope)) {
            return "school:" + scopeValue;
        } else if ("Grade".equalsIgnoreCase(scope)) {
            return "grade:" + scopeValue + ":school:" + schoolId;
        } else if ("Class".equalsIgnoreCase(scope)) {
            return "class:" + scopeValue + ":school:" + schoolId;
        }
        return "unknown:" + scopeValue;
    }

    /**
     * Record a heartbeat for a student.
     * Updates last activity time.
     */
    public void recordHeartbeat(String studentEmail) {
        studentTrackers
            .computeIfAbsent(studentEmail, k -> new StudentMinuteTracker())
            .recordHeartbeat();
        this.lastActivity = LocalDateTime.now();
    }

    /**
     * Rotate all student trackers to next minute.
     */
    public void rotateMinute() {
        studentTrackers.values().forEach(StudentMinuteTracker::rotateMinute);
    }

    /**
     * Calculate statistics for all students.
     */
    public Map<String, AttendanceStats> calculateStats() {
        Map<String, AttendanceStats> stats = new HashMap<>();

        studentTrackers.forEach((email, tracker) -> {
            stats.put(email, new AttendanceStats(
                email,
                tracker.isCurrentlyActive(),
                tracker.getLast3Minutes(),
                tracker.getTotalActiveMinutes(),
                totalMinutes,
                tracker.calculatePercentage(totalMinutes),
                tracker.calculateIsActive(totalMinutes)
            ));
        });

        return stats;
    }

    /**
     * Has this rule context had no activity recently?
     * Used for cleanup of stale trackers.
     */
    public boolean isStale(int minutesThreshold) {
        return lastActivity.plusMinutes(minutesThreshold).isBefore(LocalDateTime.now());
    }

    /**
     * Is this rule currently active (between start and end)?
     */
    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(startTime) && !now.isAfter(endTime);
    }

    /**
     * Has this rule ended?
     */
    public boolean hasEnded() {
        return LocalDateTime.now().isAfter(endTime);
    }

    // Getters
    public String getRuleContextKey() {
        return ruleContextKey;
    }

    public String getScope() {
        return scope;
    }

    public String getScopeValue() {
        return scopeValue;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public int getTotalMinutes() {
        return totalMinutes;
    }

    public int getStudentCount() {
        return studentTrackers.size();
    }

    public LocalDateTime getLastActivity() {
        return lastActivity;
    }

    public Map<String, StudentMinuteTracker> getStudentTrackers() {
        return new HashMap<>(studentTrackers);
    }
}
