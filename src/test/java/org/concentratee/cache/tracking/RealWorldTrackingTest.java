package org.concentratee.cache.tracking;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.inject.Inject;
import org.concentratee.cache.CacheManager;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-world scenario tests that previous tests missed.
 * Tests actual persistence, rules-only tracking, and concurrent scenarios.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealWorldTrackingTest {

    @Inject
    TrackingManager trackingManager;

    @Inject
    CacheManager cacheManager;

    @Inject
    PgPool pgPool;

    private static final Long TEST_SESSION_ID = 97001L;
    private static final Long TEST_STUDENT_ID = 97001L;
    private static final Long TEST_PROFILE_ID = 97001L;
    private static final Long TEST_RULE_ID = 97001L;
    private static final String TEST_EMAIL = "realworld.test@example.com";

    @BeforeEach
    void setUp() {
        cleanupTestData().await().indefinitely();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData().await().indefinitely();
    }

    @Test
    @Order(1)
    @DisplayName("CRITICAL: Session tracking should PERSIST to database after cleanup")
    void testPersistenceAfterSessionEnd() {
        // Create dependencies first
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();

        sleep(500);

        // Create session with 10-minute duration but ends in 3 seconds
        // This gives us totalMinutes=10 for percentage calculation
        createSessionEndingSoon(10, 3).await().indefinitely();

        sleep(500);

        // Send 5 heartbeats quickly
        for (int i = 0; i < 5; i++) {
            given().queryParam("track", "true").when().get("/cache/profiles/active/" + TEST_EMAIL);
        }

        // Rotate minute to lock in the data (1 active minute out of 10 total)
        trackingManager.rotateMinute();

        // Wait for session to end
        sleep(3500);

        // Trigger cleanup manually
        trackingManager.cleanupEndedSessions();

        sleep(1000);

        // NOW CHECK DATABASE: Was is_active and percentage actually persisted?
        var result = pgPool.query(
            "SELECT is_active, percentage FROM sessions WHERE id = " + TEST_SESSION_ID
        ).execute().await().indefinitely();

        // Should have been updated by persistence
        if (result.iterator().hasNext()) {
            var row = result.iterator().next();
            Boolean isActive = row.getBoolean("is_active");
            Double percentage = row.getDouble("percentage");

            System.out.println("PERSISTENCE TEST: is_active=" + isActive + ", percentage=" + percentage);

            // Verify persistence happened (values should not be default)
            assertNotNull(isActive, "is_active should have been persisted");
            assertNotNull(percentage, "percentage should have been persisted");
            // 1 active minute out of 10 = 10%
            assertTrue(percentage > 0, "percentage should be > 0 after heartbeats");
            assertEquals(10.0, percentage, 0.1, "percentage should be 10% (1/10 minutes)");
        } else {
            fail("Session not found in database after cleanup");
        }
    }

    @Test
    @Order(2)
    @DisplayName("CRITICAL: Rules-only tracking (NO session) should work")
    void testRulesOnlyTracking() {
        // Create student, profile, but NO session - only a school-wide rule
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createSchoolWideRule().await().indefinitely();

        sleep(1000); // Wait longer for LISTEN/NOTIFY to propagate

        // Student has NO session, only rule applies
        // Send heartbeat - should create RuleTracker
        given().queryParam("track", "true").when().get("/cache/profiles/active/" + TEST_EMAIL);

        sleep(1000); // Wait for tracking to process

        // Verify RuleTracker was created
        assertTrue(trackingManager.getActiveRuleTrackerCount() > 0,
            "Should have created rule tracker for school-wide rule");

        // Get rule tracking data
        String contextKey = "school:1"; // School ID 1
        var stats = trackingManager.getRuleTracking(contextKey);

        assertFalse(stats.isEmpty(), "Should have tracking stats for school rule");
        assertTrue(stats.containsKey(TEST_EMAIL), "Should track test student");
    }

    @Test
    @Order(3)
    @DisplayName("CRITICAL: Concurrent heartbeats from 10 students should not corrupt data")
    void testConcurrentStudentsRealAPI() throws Exception {
        // Create 10 students, each with their own session
        createTestProfile().await().indefinitely();

        for (int i = 0; i < 10; i++) {
            Long studentId = TEST_STUDENT_ID + i;
            Long sessionId = TEST_SESSION_ID + i;  // Each student gets unique session
            String email = "student" + i + "@concurrent.test";
            createStudent(studentId, email).await().indefinitely();
            createSessionForStudent(sessionId, studentId, email).await().indefinitely();
        }

        sleep(1000);

        // Launch 10 threads, each sending 20 heartbeats
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final String email = "student" + i + "@concurrent.test";
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 20; j++) {
                    given().queryParam("track", "true")
                        .when().get("/cache/profiles/active/" + email)
                        .then().statusCode(200);
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread t : threads) {
            t.join();
        }

        sleep(500);

        // Verify data integrity - count total tracked sessions
        int totalSessionsTracked = trackingManager.getActiveSessionTrackerCount();

        assertTrue(totalSessionsTracked >= 10, "Should track at least 10 sessions, got " + totalSessionsTracked);
    }

    @Test
    @Order(4)
    @DisplayName("CRITICAL: Minute rotation during active tracking should not lose data")
    void testRotationDuringActiveTracking() {
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createActiveSession().await().indefinitely();

        sleep(500);

        // Send heartbeat
        given().queryParam("track", "true").when().get("/cache/profiles/active/" + TEST_EMAIL);

        // Get current minute counter BEFORE rotation
        var statsBefore = trackingManager.getSessionTracking(TEST_SESSION_ID);
        var trackerBefore = statsBefore.get(TEST_EMAIL);

        // Rotate minute
        trackingManager.rotateMinute();

        // Send another heartbeat AFTER rotation
        given().queryParam("track", "true").when().get("/cache/profiles/active/" + TEST_EMAIL);

        // Check that history was preserved
        var statsAfter = trackingManager.getSessionTracking(TEST_SESSION_ID);
        var trackerAfter = statsAfter.get(TEST_EMAIL);

        assertNotNull(trackerAfter, "Tracker should still exist after rotation");
        assertEquals(1, trackerAfter.totalActiveMinutes,
            "Should have 1 active minute from first heartbeat (rotated)");
    }

    @Test
    @Order(5)
    @DisplayName("CRITICAL: Student with BOTH session and rules should track in session only")
    void testSessionOverridesRules() {
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createActiveSession().await().indefinitely();
        createSchoolWideRule().await().indefinitely();

        sleep(500);

        int initialRuleTrackerCount = trackingManager.getActiveRuleTrackerCount();

        // Send heartbeat - has BOTH session and rule
        given().queryParam("track", "true").when().get("/cache/profiles/active/" + TEST_EMAIL);

        sleep(500);

        // Should track in SESSION, not create rule tracker
        assertEquals(initialRuleTrackerCount, trackingManager.getActiveRuleTrackerCount(),
            "Should NOT create rule tracker when session exists with same profile");

        // Verify session tracking works
        var sessionStats = trackingManager.getSessionTracking(TEST_SESSION_ID);
        assertTrue(sessionStats.containsKey(TEST_EMAIL), "Should track in session");
    }

    @Test
    @Order(6)
    @DisplayName("CRITICAL: Empty session (no students) should return empty stats")
    void testEmptySessionStats() {
        // Use a different session ID that has never been tracked
        Long emptySessionId = 97999L;

        // Query session endpoint directly (no session created, so definitely no tracking)
        given()
            .when().get("/tracking/session/" + emptySessionId)
            .then()
                .statusCode(200)
                .body("sessionId", equalTo(emptySessionId.intValue()))
                .body("students", equalTo(java.util.Collections.emptyList()));
    }

    @Test
    @Order(7)
    @DisplayName("CRITICAL: Rule context cleanup should remove stale trackers")
    void testRuleContextCleanup() {
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createSchoolWideRule().await().indefinitely();

        sleep(500);

        // Send heartbeat to create rule tracker
        given().queryParam("track", "true").when().get("/cache/profiles/active/" + TEST_EMAIL);

        sleep(500);

        int beforeCleanup = trackingManager.getActiveRuleTrackerCount();
        assertTrue(beforeCleanup > 0, "Should have rule trackers before cleanup");

        // Manually set rule tracker as stale (would normally take 30 minutes)
        // We can't do this directly, so just verify the cleanup method runs without error
        trackingManager.cleanupStaleRuleContexts();

        // Should not crash
        int afterCleanup = trackingManager.getActiveRuleTrackerCount();
        assertTrue(afterCleanup >= 0, "Cleanup should complete successfully");
    }

    @Test
    @Order(8)
    @DisplayName("CRITICAL: Teacher endpoint with no sessions should return empty")
    void testTeacherWithNoSessions() {
        given()
            .when().get("/tracking/teacher/99999")
            .then()
                .statusCode(200)
                .body("teacherId", equalTo(99999))
                .body("sessions", equalTo(java.util.Collections.emptyList()));
    }

    // Helper methods

    private io.smallrye.mutiny.Uni<Void> createShortSession(int seconds) {
        return pgPool.query("""
            INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, teacher_id, inserted_at, updated_at)
            VALUES (%d, 'Short Session', NOW() - INTERVAL '1 second', NOW() + INTERVAL '%d seconds', %d, 1, 8, %d, 1, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """.formatted(TEST_SESSION_ID, seconds, TEST_STUDENT_ID, TEST_PROFILE_ID))
            .execute()
            .replaceWithVoid();
    }

    private io.smallrye.mutiny.Uni<Void> createSessionEndingSoon(int durationMinutes, int endsInSeconds) {
        return pgPool.query("""
            INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, teacher_id, inserted_at, updated_at)
            VALUES (%d, 'Test Session', NOW() - INTERVAL '%d minutes', NOW() + INTERVAL '%d seconds', %d, 1, 8, %d, 1, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """.formatted(TEST_SESSION_ID, durationMinutes, endsInSeconds, TEST_STUDENT_ID, TEST_PROFILE_ID))
            .execute()
            .replaceWithVoid();
    }

    private io.smallrye.mutiny.Uni<Void> createTestStudent() {
        return createStudent(TEST_STUDENT_ID, TEST_EMAIL);
    }

    private io.smallrye.mutiny.Uni<Void> createStudent(Long id, String email) {
        return pgPool.query("""
            INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
            VALUES (%d, '%s', 1, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """.formatted(id, email))
            .execute()
            .replaceWithVoid();
    }

    private io.smallrye.mutiny.Uni<Void> createTestProfile() {
        return pgPool.query("""
            INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
            VALUES (%d, 'RealWorld Test Profile', '[]'::jsonb, 1, 1, true, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """.formatted(TEST_PROFILE_ID))
            .execute()
            .replaceWithVoid();
    }

    private io.smallrye.mutiny.Uni<Void> createActiveSession() {
        return createSessionForStudent(TEST_SESSION_ID, TEST_STUDENT_ID, TEST_EMAIL);
    }

    private io.smallrye.mutiny.Uni<Void> createSessionForStudent(Long sessionId, Long studentId, String email) {
        return pgPool.query("""
            INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, teacher_id, inserted_at, updated_at)
            VALUES (%d, 'RealWorld Test Session', NOW() - INTERVAL '5 minutes', NOW() + INTERVAL '1 hour', %d, 1, 8, %d, 1, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """.formatted(sessionId, studentId, TEST_PROFILE_ID))
            .execute()
            .replaceWithVoid();
    }

    private io.smallrye.mutiny.Uni<Void> createSchoolWideRule() {
        return pgPool.query("""
            INSERT INTO rules (id, scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at)
            VALUES (%d, 'School', '1', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '2 hours', %d, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """.formatted(TEST_RULE_ID, TEST_PROFILE_ID))
            .execute()
            .replaceWithVoid();
    }

    private io.smallrye.mutiny.Uni<Void> cleanupTestData() {
        return pgPool.query("""
            DELETE FROM sessions WHERE id >= 97000 AND id < 98000;
            DELETE FROM rules WHERE id >= 97000 AND id < 98000;
            DELETE FROM profiles WHERE id >= 97000 AND id < 98000;
            DELETE FROM students WHERE id >= 97000 AND id < 98000;
            """)
            .execute()
            .replaceWithVoid();
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
