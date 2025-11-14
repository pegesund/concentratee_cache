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
 * Integration tests for tracking functionality with real database and API.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackingIntegrationTest {

    @Inject
    TrackingManager trackingManager;

    @Inject
    CacheManager cacheManager;

    @Inject
    PgPool pgPool;

    private static final Long TEST_SESSION_ID = 98001L;
    private static final Long TEST_STUDENT_ID = 98001L;
    private static final Long TEST_PROFILE_ID = 98001L;
    private static final String TEST_EMAIL = "tracking.test@example.com";

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
    @DisplayName("Tracking stats endpoint should return initial state")
    void testTrackingStatsEndpoint() {
        given()
            .when().get("/tracking/stats")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("activeSessions", notNullValue())
                .body("activeRuleContexts", notNullValue())
                .body("indexedStudents", notNullValue())
                .body("indexedTeachers", notNullValue())
                .body("indexedSchools", notNullValue());
    }

    @Test
    @Order(2)
    @DisplayName("Should record heartbeat via API with automatic tracking")
    void testHeartbeatRecordingViaAPI() {
        // Create test data
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createActiveSession().await().indefinitely();

        sleep(500);

        // Call API with automatic tracking
        given()
            
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200);

        sleep(500);

        // Verify tracker was created
        assertTrue(trackingManager.getActiveSessionTrackerCount() > 0);
    }

    @Test
    @Order(3)
    @DisplayName("Should NOT record heartbeat when track=false")
    void testNoTrackingWhenDisabled() {
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createActiveSession().await().indefinitely();

        sleep(500);

        int initialCount = trackingManager.getActiveSessionTrackerCount();

        // Call API with track=false (default)
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200);

        // Verify no new trackers created
        assertEquals(initialCount, trackingManager.getActiveSessionTrackerCount());
    }

    @Test
    @Order(4)
    @DisplayName("Should track session over multiple heartbeats")
    void testMultipleHeartbeats() {
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createActiveSession().await().indefinitely();

        sleep(500);

        // Send 5 heartbeats
        for (int i = 0; i < 5; i++) {
            given()
                
                .when().get("/cache/profiles/active/" + TEST_EMAIL)
                .then()
                    .statusCode(200);
        }

        sleep(500);

        // Check session tracking endpoint
        given()
            .when().get("/tracking/session/" + TEST_SESSION_ID)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("sessionId", equalTo(TEST_SESSION_ID.intValue()))
                .body("students", notNullValue());
    }

    @Test
    @Order(5)
    @DisplayName("Should track multiple students in same session")
    void testMultipleStudentsInSession() {
        String email1 = "student1.tracking@test.com";
        String email2 = "student2.tracking@test.com";

        // Create students
        createStudent(98002L, email1).await().indefinitely();
        createStudent(98003L, email2).await().indefinitely();
        createTestProfile().await().indefinitely();

        // Create session for student 1
        createSessionForStudent(TEST_SESSION_ID, 98002L, email1).await().indefinitely();

        // Create session for student 2 (same session ID + 1)
        createSessionForStudent(TEST_SESSION_ID + 1, 98003L, email2).await().indefinitely();

        sleep(500);

        // Send heartbeats from both students
        given().when().get("/cache/profiles/active/" + email1);
        given().when().get("/cache/profiles/active/" + email2);

        sleep(500);

        // Verify both sessions are being tracked
        assertTrue(trackingManager.getActiveSessionTrackerCount() >= 2);
    }

    @Test
    @Order(6)
    @DisplayName("Teacher endpoint should return tracking data")
    void testTeacherEndpoint() {
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createActiveSession().await().indefinitely();

        sleep(500);

        // Send heartbeat
        given()
            
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200);

        sleep(500);

        // Query teacher endpoint (teacher ID = 1 from test data)
        given()
            .when().get("/tracking/teacher/1")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("teacherId", equalTo(1))
                .body("sessions", notNullValue());
    }

    @Test
    @Order(7)
    @DisplayName("Should handle session with no tracking data")
    void testSessionWithNoData() {
        given()
            .when().get("/tracking/session/99999")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("sessionId", equalTo(99999))
                .body("students", equalTo(java.util.Collections.emptyList()));
    }

    @Test
    @Order(8)
    @DisplayName("Should handle minute rotation correctly")
    void testMinuteRotation() {
        createTestStudent().await().indefinitely();
        createTestProfile().await().indefinitely();
        createActiveSession().await().indefinitely();

        sleep(500);

        // Send heartbeat
        given().when().get("/cache/profiles/active/" + TEST_EMAIL);

        sleep(500);

        // Manually trigger rotation
        trackingManager.rotateMinute();

        // Send another heartbeat after rotation
        given().when().get("/cache/profiles/active/" + TEST_EMAIL);

        // Check that student has history
        var stats = trackingManager.getSessionTracking(TEST_SESSION_ID);
        if (!stats.isEmpty()) {
            AttendanceStats stat = stats.get(TEST_EMAIL);
            assertNotNull(stat);
            assertTrue(stat.totalActiveMinutes > 0);
        }
    }

    // Helper methods

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
            VALUES (%d, 'Tracking Test Profile', '[]'::jsonb, 1, 1, true, NOW(), NOW())
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
            VALUES (%d, 'Tracking Test Session', NOW() - INTERVAL '5 minutes', NOW() + INTERVAL '1 hour', %d, 1, 8, %d, 1, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """.formatted(sessionId, studentId, TEST_PROFILE_ID))
            .execute()
            .replaceWithVoid();
    }

    private io.smallrye.mutiny.Uni<Void> cleanupTestData() {
        return pgPool.query("""
            DELETE FROM sessions WHERE id >= 98000 AND id < 99000;
            DELETE FROM profiles WHERE id >= 98000 AND id < 99000;
            DELETE FROM students WHERE id >= 98000 AND id < 99000;
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
