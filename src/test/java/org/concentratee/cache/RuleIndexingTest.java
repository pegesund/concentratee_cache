package org.concentratee.cache;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * Integration tests for rule indexing.
 *
 * These tests verify that the buildRulesByScopeAndValueIndex() method
 * properly indexes ALL rules, including:
 * - Rules with NULL scope_value (wildcards)
 * - Rules with empty string scope_value (wildcards)
 * - Rules with specific scope_value
 *
 * This prevents the bug where wildcard rules were excluded from the index
 * due to the check: if (rule.scope != null && rule.scopeValue != null)
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuleIndexingTest {

    @Inject
    PgPool client;

    @Inject
    CacheManager cacheManager;

    private static final String TEST_EMAIL = "test@example.com";
    private static Long testProfileId;
    private static Long emptyStringRuleId;
    private static Long nullValueRuleId;
    private static Long specificRuleId;

    @BeforeAll
    void setupTestData() throws Exception {
        // Create a test profile
        var profileResult = client.query(
            "INSERT INTO profiles (name, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at) " +
            "VALUES ('Rule Indexing Test Profile', 1, 1, true, NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        testProfileId = profileResult.iterator().next().getLong("id");

        // Give cache time to update
        Thread.sleep(1000);
    }

    @AfterAll
    void cleanupTestData() throws Exception {
        // Clean up rules first (due to foreign key constraints)
        if (emptyStringRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + emptyStringRuleId).execute().await().indefinitely();
        }
        if (nullValueRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + nullValueRuleId).execute().await().indefinitely();
        }
        if (specificRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + specificRuleId).execute().await().indefinitely();
        }

        // Then clean up profile
        if (testProfileId != null) {
            client.query("DELETE FROM profiles WHERE id = " + testProfileId).execute().await().indefinitely();
        }

        Thread.sleep(1000);
    }

    @Test
    @Order(1)
    @DisplayName("Initial state - verify rulesByScopeAndValue index is working")
    void testInitialIndexState() {
        var stats = cacheManager.getCacheStats();

        // The index should exist (may be 0 or more depending on existing data)
        Assertions.assertNotNull(stats.get("rulesByScopeAndValue"),
            "rulesByScopeAndValue index should not be null");
    }

    @Test
    @Order(2)
    @DisplayName("Rule with empty string scope_value should be indexed and matchable")
    void testEmptyStringRuleIsIndexed() throws Exception {
        // Create a School rule with EMPTY STRING scope_value
        var result = client.query(
            "INSERT INTO rules (scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at) " +
            "VALUES ('School', '', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 hour', " + testProfileId + ", NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        emptyStringRuleId = result.iterator().next().getLong("id");

        // Wait for LISTEN/NOTIFY to propagate
        cacheManager.waitForNotifications(2000);

        // Verify the rule is in the main rules cache
        var rule = cacheManager.getRule(emptyStringRuleId);
        Assertions.assertNotNull(rule, "Rule should exist in rulesById cache");
        Assertions.assertEquals("School", rule.scope, "Rule scope should be School");
        Assertions.assertEquals("", rule.scopeValue, "Rule scope_value should be empty string");

        // Verify the rule can be looked up via getRulesByScope
        var emptyStringRules = cacheManager.getRulesByScope("School", "");
        Assertions.assertFalse(emptyStringRules.isEmpty(),
            "Should be able to lookup rules with empty string scope_value");
        Assertions.assertTrue(
            emptyStringRules.stream().anyMatch(r -> r.id.equals(emptyStringRuleId)),
            "The created rule should be findable via getRulesByScope with empty string"
        );

        // Verify the rule matches students
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(testProfileId.intValue()));
    }

    @Test
    @Order(3)
    @DisplayName("Rule with NULL scope_value should be indexed as empty string")
    void testNullValueRuleIsIndexed() throws Exception {
        // Create a Grade rule with NULL scope_value (SQL NULL, not empty string)
        var result = client.query(
            "INSERT INTO rules (scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at) " +
            "VALUES ('Grade', NULL, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 hour', " + testProfileId + ", NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        nullValueRuleId = result.iterator().next().getLong("id");

        // Wait for LISTEN/NOTIFY to propagate
        cacheManager.waitForNotifications(2000);

        // Verify the rule is in the main rules cache
        var rule = cacheManager.getRule(nullValueRuleId);
        Assertions.assertNotNull(rule, "Rule should exist in rulesById cache");
        Assertions.assertEquals("Grade", rule.scope, "Rule scope should be Grade");

        // The scopeValue in Java might be null or empty string, but it should be indexed as empty string
        // Verify the rule can be looked up via getRulesByScope with empty string
        var nullRules = cacheManager.getRulesByScope("Grade", "");
        Assertions.assertFalse(nullRules.isEmpty(),
            "Should be able to lookup rules with NULL scope_value via empty string");
        Assertions.assertTrue(
            nullRules.stream().anyMatch(r -> r.id.equals(nullValueRuleId)),
            "The created rule with NULL scope_value should be findable via empty string lookup"
        );

        // Verify the rule matches students
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(testProfileId.intValue()));
    }

    @Test
    @Order(4)
    @DisplayName("Rule with specific scope_value should be indexed separately")
    void testSpecificValueRuleIsIndexed() throws Exception {
        // Get the student's school_id
        var studentResult = client.query(
            "SELECT school_id FROM students WHERE id = 42"
        ).execute().await().indefinitely();
        Long schoolId = studentResult.iterator().next().getLong("school_id");

        // Create a Class rule with SPECIFIC scope_value
        var result = client.query(
            "INSERT INTO rules (scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at) " +
            "VALUES ('Class', '" + schoolId + "', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 hour', " + testProfileId + ", NOW(), NOW()) " +
            "RETURNING id"
        ).execute().await().indefinitely();
        specificRuleId = result.iterator().next().getLong("id");

        // Wait for LISTEN/NOTIFY to propagate
        cacheManager.waitForNotifications(2000);

        // Verify the rule is in the main rules cache
        var rule = cacheManager.getRule(specificRuleId);
        Assertions.assertNotNull(rule, "Rule should exist in rulesById cache");
        Assertions.assertEquals("Class", rule.scope, "Rule scope should be Class");
        Assertions.assertEquals(String.valueOf(schoolId), rule.scopeValue,
            "Rule scope_value should match school_id");

        // Verify the rule can be looked up via getRulesByScope with specific value
        var specificRules = cacheManager.getRulesByScope("Class", String.valueOf(schoolId));
        Assertions.assertFalse(specificRules.isEmpty(),
            "Should be able to lookup rules with specific scope_value");
        Assertions.assertTrue(
            specificRules.stream().anyMatch(r -> r.id.equals(specificRuleId)),
            "The created rule should be findable via getRulesByScope with specific value"
        );

        // Verify empty string lookup does NOT return this specific rule
        var emptyRules = cacheManager.getRulesByScope("Class", "");
        Assertions.assertFalse(
            emptyRules.stream().anyMatch(r -> r.id.equals(specificRuleId)),
            "Specific value rules should NOT be returned when looking up empty string"
        );
    }

    @Test
    @Order(5)
    @DisplayName("Cache stats should show rules indexed after all insertions")
    void testCacheStatsShowIndexedRules() {
        var stats = cacheManager.getCacheStats();

        // We created 3 rules, so rulesById should have at least 3
        int rulesById = (Integer) stats.get("rulesById");
        Assertions.assertTrue(rulesById >= 3,
            "rulesById should contain at least our 3 test rules, found: " + rulesById);

        // rulesByScopeAndValue should have entries (at least School, Grade, Class scopes)
        int rulesByScopeAndValue = (Integer) stats.get("rulesByScopeAndValue");
        Assertions.assertTrue(rulesByScopeAndValue >= 3,
            "rulesByScopeAndValue should index at least 3 scopes (School, Grade, Class), found: " + rulesByScopeAndValue);
    }

    @Test
    @Order(6)
    @DisplayName("Student should match all three rule types")
    void testStudentMatchesAllRules() {
        // The student should have the test profile from ALL three rules:
        // 1. School wildcard (empty string)
        // 2. Grade wildcard (NULL -> empty string)
        // 3. Class specific (if student's class matches)

        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .body("profileIds", hasItem(testProfileId.intValue()));
    }

    @Test
    @Order(7)
    @DisplayName("Cleanup - remove all test rules")
    void testCleanup() throws Exception {
        // Remove all test rules
        if (emptyStringRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + emptyStringRuleId).execute().await().indefinitely();
            emptyStringRuleId = null;
        }
        if (nullValueRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + nullValueRuleId).execute().await().indefinitely();
            nullValueRuleId = null;
        }
        if (specificRuleId != null) {
            client.query("DELETE FROM rules WHERE id = " + specificRuleId).execute().await().indefinitely();
            specificRuleId = null;
        }

        // Wait for LISTEN/NOTIFY to propagate
        Thread.sleep(2000);

        // Verify student no longer matches the test profile
        var response = given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/" + TEST_EMAIL)
            .then()
                .statusCode(200)
                .body("email", is(TEST_EMAIL))
                .extract().path("profileIds");

        // The test profile should NOT be in the list anymore
        Assertions.assertFalse(
            ((java.util.List<?>) response).contains(testProfileId.intValue()),
            "Test profile should not be active after all rules are deleted"
        );
    }
}
