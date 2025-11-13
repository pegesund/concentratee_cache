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
}
