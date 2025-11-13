package org.concentratee.cache.tracking;

import org.concentratee.cache.model.Session;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SessionTracker.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionTrackerTest {

    private Session createTestSession(int durationMinutes) {
        Session session = new Session();
        session.id = 1L;
        session.startTime = LocalDateTime.now().minusMinutes(5);
        session.endTime = session.startTime.plusMinutes(durationMinutes);
        session.studentId = 100L;
        session.schoolId = 1L;
        return session;
    }

    @Test
    @Order(1)
    @DisplayName("Should initialize with session details")
    void testInitialization() {
        Session session = createTestSession(60);
        SessionTracker tracker = new SessionTracker(session);

        assertEquals(1L, tracker.getSessionId());
        assertEquals(60, tracker.getTotalMinutes());
        assertEquals(0, tracker.getStudentCount());
        assertTrue(tracker.isActive());
    }

    @Test
    @Order(2)
    @DisplayName("Should record heartbeat for student")
    void testRecordHeartbeat() {
        Session session = createTestSession(60);
        SessionTracker tracker = new SessionTracker(session);

        tracker.recordHeartbeat("student1@test.com");

        assertEquals(1, tracker.getStudentCount());
        assertTrue(tracker.getStudentTrackers().containsKey("student1@test.com"));
    }

    @Test
    @Order(3)
    @DisplayName("Should record heartbeats for multiple students")
    void testMultipleStudents() {
        Session session = createTestSession(60);
        SessionTracker tracker = new SessionTracker(session);

        tracker.recordHeartbeat("student1@test.com");
        tracker.recordHeartbeat("student2@test.com");
        tracker.recordHeartbeat("student3@test.com");

        assertEquals(3, tracker.getStudentCount());
    }

    @Test
    @Order(4)
    @DisplayName("Should rotate all student trackers")
    void testRotateMinute() {
        Session session = createTestSession(60);
        SessionTracker tracker = new SessionTracker(session);

        tracker.recordHeartbeat("student1@test.com");
        tracker.recordHeartbeat("student2@test.com");

        tracker.rotateMinute();

        // Check that rotation happened
        Map<String, StudentMinuteTracker> students = tracker.getStudentTrackers();
        assertEquals(0, students.get("student1@test.com").getCurrentMinuteCounter());
        assertEquals(1, students.get("student1@test.com").getTotalActiveMinutes());
    }

    @Test
    @Order(5)
    @DisplayName("Should calculate stats for all students")
    void testCalculateStats() {
        Session session = createTestSession(30);
        SessionTracker tracker = new SessionTracker(session);

        // Student 1: active for 25 minutes
        for (int i = 0; i < 25; i++) {
            tracker.recordHeartbeat("student1@test.com");
            tracker.rotateMinute();
        }
        // Inactive for last 5
        for (int i = 0; i < 5; i++) {
            tracker.rotateMinute();
        }

        Map<String, AttendanceStats> stats = tracker.calculateStats();

        assertTrue(stats.containsKey("student1@test.com"));
        AttendanceStats stat = stats.get("student1@test.com");
        assertEquals(25, stat.totalActiveMinutes);
        assertEquals(30, stat.totalMinutes);
        assertEquals(83.33, stat.percentage, 0.01);
        assertTrue(stat.isActive); // 25/30 = 83.3% > 80%
    }

    @Test
    @Order(6)
    @DisplayName("Should get stats for single student")
    void testGetStatsForStudent() {
        Session session = createTestSession(10);
        SessionTracker tracker = new SessionTracker(session);

        tracker.recordHeartbeat("student1@test.com");
        tracker.rotateMinute();

        AttendanceStats stats = tracker.getStatsForStudent("student1@test.com");

        assertEquals("student1@test.com", stats.email);
        assertEquals(1, stats.totalActiveMinutes);
        assertEquals(10, stats.totalMinutes);
    }

    @Test
    @Order(7)
    @DisplayName("Should handle student with no data")
    void testGetStatsForNonExistentStudent() {
        Session session = createTestSession(10);
        SessionTracker tracker = new SessionTracker(session);

        AttendanceStats stats = tracker.getStatsForStudent("nonexistent@test.com");

        assertEquals("nonexistent@test.com", stats.email);
        assertEquals(0, stats.totalActiveMinutes);
        assertFalse(stats.isActive);
    }

    @Test
    @Order(8)
    @DisplayName("Should detect ended session")
    void testHasEnded() {
        Session session = createTestSession(60);
        session.endTime = LocalDateTime.now().minusMinutes(10); // Ended 10 minutes ago
        SessionTracker tracker = new SessionTracker(session);

        assertTrue(tracker.hasEnded());
        assertFalse(tracker.isActive());
    }

    @Test
    @Order(9)
    @DisplayName("Should handle complex multi-student scenario")
    void testComplexScenario() {
        Session session = createTestSession(20);
        SessionTracker tracker = new SessionTracker(session);

        // Student 1: very active (18/20)
        for (int i = 0; i < 18; i++) {
            tracker.recordHeartbeat("student1@test.com");
            tracker.rotateMinute();
        }
        tracker.rotateMinute();
        tracker.rotateMinute();

        // Student 2: somewhat active (12/20)
        for (int i = 0; i < 12; i++) {
            tracker.recordHeartbeat("student2@test.com");
            if (i < 20) tracker.rotateMinute();
        }

        // Student 3: barely active (5/20)
        for (int i = 0; i < 5; i++) {
            tracker.recordHeartbeat("student3@test.com");
            if (i < 20) tracker.rotateMinute();
        }

        Map<String, AttendanceStats> stats = tracker.calculateStats();

        assertEquals(3, stats.size());

        AttendanceStats s1 = stats.get("student1@test.com");
        assertTrue(s1.isActive); // 18/20 = 90% > 80%

        AttendanceStats s2 = stats.get("student2@test.com");
        assertFalse(s2.isActive); // 12/20 = 60% < 80%

        AttendanceStats s3 = stats.get("student3@test.com");
        assertFalse(s3.isActive); // 5/20 = 25% < 80%
    }

    @Test
    @Order(10)
    @DisplayName("Should be thread-safe")
    void testThreadSafety() throws InterruptedException {
        Session session = createTestSession(60);
        SessionTracker tracker = new SessionTracker(session);

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final String email = "student" + i + "@test.com";
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    tracker.recordHeartbeat(email);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(10, tracker.getStudentCount());
    }
}
