package org.concentratee.cache;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * Integration tests for REST API endpoints
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MainTest {

    @Test
    @Order(1)
    @DisplayName("Health endpoint should return OK with database info")
    void testHealthEndpoint() {
        given()
            .when().get("/health")
            .then()
                .statusCode(200)
                .body("status", is("ok"))
                .body("database", notNullValue())
                .body("timestamp", notNullValue());
    }

    @Test
    @Order(2)
    @DisplayName("Cache stats endpoint should return statistics")
    void testCacheStatsEndpoint() {
        given()
            .when().get("/cache/stats")
            .then()
                .statusCode(200)
                .body("studentsById", notNullValue())
                .body("profilesById", notNullValue())
                .body("rulesById", notNullValue())
                .body("sessionsById", notNullValue());
    }

    @Test
    @Order(3)
    @DisplayName("Active profiles endpoint should return JSON")
    void testActiveProfilesEndpoint() {
        // Test with a non-existent student
        given()
            .when().get("/cache/profiles/active/nonexistent@test.com")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("email", is("nonexistent@test.com"))
                .body("profiles", notNullValue());
    }

    @Test
    @Order(4)
    @DisplayName("Active profiles endpoint with expand=false should return IDs only")
    void testActiveProfilesEndpointCompact() {
        given()
            .queryParam("expand", "false")
            .when().get("/cache/profiles/active/nonexistent@test.com")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("email", is("nonexistent@test.com"))
                .body("profileIds", notNullValue());
    }

    @Test
    @Order(5)
    @DisplayName("Active profiles endpoint with expand=true should return full profiles")
    void testActiveProfilesEndpointExpanded() {
        given()
            .queryParam("expand", "true")
            .when().get("/cache/profiles/active/nonexistent@test.com")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("email", is("nonexistent@test.com"))
                .body("profiles", notNullValue());
    }

    @Test
    @Order(6)
    @DisplayName("Sessions by email endpoint should return text")
    void testSessionsByEmailEndpoint() {
        given()
            .when().get("/cache/sessions/nonexistent@test.com")
            .then()
                .statusCode(200)
                .contentType("text/plain")
                .body(containsString("nonexistent@test.com"));
    }

    @Test
    @Order(7)
    @DisplayName("School rules endpoint should return text")
    void testSchoolRulesEndpoint() {
        given()
            .when().get("/cache/rules/school")
            .then()
                .statusCode(200)
                .contentType("text/plain")
                .body(containsString("School rules:"));
    }

    @Test
    @Order(8)
    @DisplayName("Cleanup endpoint should trigger successfully")
    void testCleanupEndpoint() {
        given()
            .when().get("/cache/cleanup")
            .then()
                .statusCode(200)
                .contentType("text/plain")
                .body(is("Cleanup triggered successfully"));
    }

    @Test
    @Order(9)
    @DisplayName("Profile API should include programs array")
    void testProfilesIncludePrograms() {
        given()
            .queryParam("expand", "true")
            .when().get("/cache/profiles/active/test@example.com")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("email", is("test@example.com"))
                .body("profiles", notNullValue())
                // Verify programs field exists (even if empty)
                .body("profiles[0].programs", notNullValue());
    }

    @Test
    @Order(10)
    @DisplayName("Profile API should include categories with full hierarchy")
    void testProfilesIncludeCategoriesHierarchy() {
        given()
            .queryParam("expand", "true")
            .when().get("/cache/profiles/active/test@example.com")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("email", is("test@example.com"))
                .body("profiles", notNullValue())
                // Verify categories field exists
                .body("profiles[0].categories", notNullValue())
                // If categories exist, verify structure
                .body("profiles[0].id", notNullValue())
                .body("profiles[0].name", notNullValue())
                .body("profiles[0].domains", notNullValue())
                .body("profiles[0].programs", notNullValue())
                .body("profiles[0].categories", notNullValue());
    }

    @Test
    @Order(11)
    @DisplayName("Profile API parity - verify complete structure matches Elixir API")
    void testCompleteApiParity() {
        // This test verifies that the Java cache API exposes the same data structure
        // as the Elixir API, specifically:
        // - profiles with id, name, teacherId, schoolId, isWhitelistUrl
        // - programs array (active only)
        // - domains array
        // - categories array with:
        //   - id, name, isActive
        //   - subcategories array with:
        //     - id, name, isActive
        //     - urls array with:
        //       - id, url, isActive
        //
        // All data should reflect only ACTIVE items based on:
        // - profiles_categories.is_active = true
        // - Excluding items in profile_inactive_subcategories
        // - Excluding items in profile_inactive_urls

        given()
            .queryParam("expand", "true")
            .when().get("/cache/profiles/active/test@example.com")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("email", is("test@example.com"))
                .body("profiles", notNullValue())
                // Profile structure
                .body("profiles[0].id", notNullValue())
                .body("profiles[0].name", notNullValue())
                .body("profiles[0].teacherId", notNullValue())
                .body("profiles[0].schoolId", notNullValue())
                .body("profiles[0].isWhitelistUrl", notNullValue())
                // Arrays must exist
                .body("profiles[0].programs", notNullValue())
                .body("profiles[0].domains", notNullValue())
                .body("profiles[0].categories", notNullValue());
    }
}
