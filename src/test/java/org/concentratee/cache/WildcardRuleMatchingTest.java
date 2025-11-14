package org.concentratee.cache;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * Integration tests for wildcard rule matching.
 *
 * Tests verify that rules with empty scope_value (wildcard rules) properly match
 * all entities of that scope type (all schools, all grades, all classes).
 *
 * Test scenarios:
 * 1. School-wide wildcard rule (scope_value = empty) matches all students in any school
 * 2. Grade-level wildcard rule (scope_value = empty) matches all students in any grade
 * 3. Class-level wildcard rule (scope_value = empty) matches all students in any class
 * 4. Specific rules still work alongside wildcard rules
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WildcardRuleMatchingTest {

    @Inject
    PgPool client;

    @Inject
    CacheManager cacheManager;

    private static final String TEST_EMAIL = "test@example.com";
    private static final Long TEST_PROFILE_ID = 1L;
    private static Long wildcardProfileId;
    private static Long wildcardRuleId;
    private static Long specificProfileId;
    private static Long specificRuleId;

    @BeforeAll
    void setupTestData() throws Exception {
        // Create a wildcard test profile
        var wildcardProfileResult = client.query(
            "INSERT INTO profiles (name, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at) " +
            "VALUES ('Wildcard Test Profile', 1, 1, true, NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        wildcardProfileId = wildcardProfileResult.iterator().next().getLong("id");

        // Create a specific test profile
        var specificProfileResult = client.query(
            "INSERT INTO profiles (name, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at) " +
            "VALUES ('Specific Test Profile', 1, 1, true, NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        specificProfileId = specificProfileResult.iterator().next().getLong("id");

        // Give cache time to update
        Thread.sleep(1000);
    }

    @AfterAll
    void cleanupTestData() throws Exception {
        // Clean up rules first (due to foreign key constraints)
        if (wildcardRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + wildcardRuleId).execute().await().indefinitely();
        }
        if (specificRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + specificRuleId).execute().await().indefinitely();
        }

        // Then clean up profiles
        if (wildcardProfileId != null) {
            client.query("DELETE FROM profiles WHERE id = " + wildcardProfileId).execute().await().indefinitely();
        }
        if (specificProfileId != null) {
            client.query("DELETE FROM profiles WHERE id = " + specificProfileId).execute().await().indefinitely();
        }

        Thread.sleep(1000);
    }

    @Test
    @Order(1)
    @DisplayName("Initial state - no wildcard rules active")
    void testInitialStateNoWildcardRules() {
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", notNullValue());
    }

    @Test
    @Order(2)
    @DisplayName("School-wide wildcard rule should match all students")
    void testSchoolWildcardRuleMatches() throws Exception {
        // Create a School rule with EMPTY scope_value (wildcard)
        var result = client.query(
            "INSERT INTO rules (scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at) " +
            "VALUES ('School', '', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 hour', " + wildcardProfileId + ", NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        wildcardRuleId = result.iterator().next().getLong("id");

        // Wait for LISTEN/NOTIFY to propagate
        cacheManager.waitForNotifications(2000);

        // Verify the wildcard rule matches
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(wildcardProfileId.intValue()));
    }

    @Test
    @Order(3)
    @DisplayName("Specific school rule should also work alongside wildcard")
    void testSpecificSchoolRuleWithWildcard() throws Exception {
        // Get the student's school_id
        var studentResult = client.query(
            "SELECT school_id FROM students WHERE id = 42"
        ).execute().await().indefinitely();
        Long schoolId = studentResult.iterator().next().getLong("school_id");

        // Create a School rule with SPECIFIC scope_value
        var result = client.query(
            "INSERT INTO rules (scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at) " +
            "VALUES ('School', '" + schoolId + "', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 hour', " + specificProfileId + ", NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        specificRuleId = result.iterator().next().getLong("id");

        // Wait for LISTEN/NOTIFY to propagate
        cacheManager.waitForNotifications(2000);

        // Verify BOTH wildcard and specific rules match
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(wildcardProfileId.intValue()))
                .body("profileIds", hasItem(specificProfileId.intValue()));
    }

    @Test
    @Order(4)
    @DisplayName("Removing wildcard rule should stop matching")
    void testRemovingWildcardRule() throws Exception {
        // Remove the wildcard rule
        client.query("DELETE FROM rules WHERE id = " + wildcardRuleId).execute().await().indefinitely();
        wildcardRuleId = null;

        // Wait for LISTEN/NOTIFY to propagate
        Thread.sleep(2000);

        // Verify wildcard rule no longer matches, but specific rule still does
        var response = given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(specificProfileId.intValue()))
                .extract().path("profileIds");

        // Wildcard profile should NOT be in the list
        Assertions.assertFalse(
            ((java.util.List<?>) response).contains(wildcardProfileId.intValue()),
            "Wildcard profile should not be active after rule deletion"
        );
    }

    @Test
    @Order(5)
    @DisplayName("Grade-level wildcard rule should match all students")
    void testGradeWildcardRuleMatches() throws Exception {
        // Create a Grade rule with EMPTY scope_value (wildcard)
        var result = client.query(
            "INSERT INTO rules (scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at) " +
            "VALUES ('Grade', '', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 hour', " + wildcardProfileId + ", NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        wildcardRuleId = result.iterator().next().getLong("id");

        // Wait for LISTEN/NOTIFY to propagate
        cacheManager.waitForNotifications(2000);

        // Verify the wildcard grade rule matches
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(wildcardProfileId.intValue()));

        // Clean up
        client.query("DELETE FROM rules WHERE id = " + wildcardRuleId).execute().await().indefinitely();
        wildcardRuleId = null;
        Thread.sleep(2000);
    }

    @Test
    @Order(6)
    @DisplayName("Class-level wildcard rule should match all students")
    void testClassWildcardRuleMatches() throws Exception {
        // Create a Class rule with EMPTY scope_value (wildcard)
        var result = client.query(
            "INSERT INTO rules (scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at) " +
            "VALUES ('Class', '', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 hour', " + wildcardProfileId + ", NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        wildcardRuleId = result.iterator().next().getLong("id");

        // Wait for LISTEN/NOTIFY to propagate
        cacheManager.waitForNotifications(2000);

        // Verify the wildcard class rule matches
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(wildcardProfileId.intValue()));

        // Clean up
        client.query("DELETE FROM rules WHERE id = " + wildcardRuleId).execute().await().indefinitely();
        wildcardRuleId = null;
        Thread.sleep(2000);
    }

    @Test
    @Order(7)
    @DisplayName("Cleanup - remove specific rule")
    void testCleanupSpecificRule() throws Exception {
        // Remove the specific rule
        if (specificRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + specificRuleId).execute().await().indefinitely();
            specificRuleId = null;
        }

        // Wait for LISTEN/NOTIFY to propagate
        Thread.sleep(2000);

        // Verify back to initial state (only session-based profiles, if any)
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL));
    }
}
