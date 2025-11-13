package org.concentratee.cache.tracking;

import org.concentratee.cache.model.Session;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks appearance/attendance for all students in a single session.
 * Thread-safe, minute-level tracking with rolling history.
 */
public class SessionTracker {

    private final Long sessionId;
    private final LocalDateTime sessionStart;
    private final LocalDateTime sessionEnd;
    private final int totalMinutes;
    private final Map<String, StudentMinuteTracker> studentTrackers = new ConcurrentHashMap<>();

    public SessionTracker(Session session) {
        this.sessionId = session.id;
        this.sessionStart = session.startTime;
        this.sessionEnd = session.endTime;
        this.totalMinutes = (int) ChronoUnit.MINUTES.between(sessionStart, sessionEnd);
    }

    /**
     * Record a heartbeat for a student.
     * Thread-safe, can be called multiple times per minute.
     */
    public void recordHeartbeat(String studentEmail) {
        studentTrackers
            .computeIfAbsent(studentEmail, k -> new StudentMinuteTracker())
            .recordHeartbeat();
    }

    /**
     * Rotate all student trackers to next minute.
     * Should be called every minute at :00 boundary.
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
     * Get statistics for a single student.
     */
    public AttendanceStats getStatsForStudent(String email) {
        StudentMinuteTracker tracker = studentTrackers.get(email);
        if (tracker == null) {
            return new AttendanceStats(email, false, java.util.List.of(), 0, totalMinutes, 0.0, false);
        }

        return new AttendanceStats(
            email,
            tracker.isCurrentlyActive(),
            tracker.getLast3Minutes(),
            tracker.getTotalActiveMinutes(),
            totalMinutes,
            tracker.calculatePercentage(totalMinutes),
            tracker.calculateIsActive(totalMinutes)
        );
    }

    /**
     * Has this session ended?
     */
    public boolean hasEnded() {
        return LocalDateTime.now().isAfter(sessionEnd);
    }

    /**
     * Is this session currently active (between start and end)?
     */
    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(sessionStart) && !now.isAfter(sessionEnd);
    }

    // Getters
    public Long getSessionId() {
        return sessionId;
    }

    public LocalDateTime getSessionStart() {
        return sessionStart;
    }

    public LocalDateTime getSessionEnd() {
        return sessionEnd;
    }

    public int getTotalMinutes() {
        return totalMinutes;
    }

    public int getStudentCount() {
        return studentTrackers.size();
    }

    public Map<String, StudentMinuteTracker> getStudentTrackers() {
        return new HashMap<>(studentTrackers);
    }
}
