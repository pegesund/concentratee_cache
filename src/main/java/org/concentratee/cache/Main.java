package org.concentratee.cache;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.Map;

@Path("/")
@ApplicationScoped
public class Main {

    private static final Logger LOG = Logger.getLogger(Main.class);

    @Inject
    PgPool client;

    @Inject
    CacheManager cacheManager;

    @Inject
    org.concentratee.cache.tracking.TrackingManager trackingManager;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Application starting up...");

        // Test database connection on startup
        client.query("SELECT version()").execute()
            .subscribe().with(
                pgRowSet -> {
                    pgRowSet.forEach(row -> {
                        LOG.info("✅ Database connected successfully!");
                        LOG.info("PostgreSQL version: " + row.getString(0));
                    });
                },
                failure -> {
                    LOG.error("❌ Failed to connect to database: " + failure.getMessage());
                }
            );
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<String> health() {
        return client.query("SELECT current_timestamp, current_database()").execute()
            .onItem().transform(pgRowSet -> {
                var row = pgRowSet.iterator().next();
                return String.format("{\"status\":\"ok\",\"database\":\"%s\",\"timestamp\":\"%s\"}",
                    row.getValue(1), row.getValue(0));
            })
            .onFailure().recoverWithItem(failure ->
                String.format("{\"status\":\"error\",\"message\":\"%s\"}", failure.getMessage())
            );
    }

    @GET
    @Path("/cache/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public String cacheStats() {
        Map<String, Object> stats = cacheManager.getCacheStats();
        return String.format(
            "{\"studentsById\":%d,\"profilesById\":%d,\"rulesById\":%d,\"sessionsById\":%d,\"sessionsByEmail\":%d,\"sessionsByProfile\":%d,\"rulesByScopeAndValue\":%d}",
            stats.get("studentsById"),
            stats.get("profilesById"),
            stats.get("rulesById"),
            stats.get("sessionsById"),
            stats.get("sessionsByEmail"),
            stats.get("sessionsByProfile"),
            stats.get("rulesByScopeAndValue")
        );
    }

    @GET
    @Path("/cache/sessions/{email}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getSessionsByEmail(@PathParam("email") String email) {
        var sessions = cacheManager.getSessionsByEmail(email);
        if (sessions.isEmpty()) {
            return "No sessions found for: " + email;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Sessions for ").append(email).append(":\n");
        sessions.forEach(s -> sb.append("  - ").append(s).append("\n"));
        return sb.toString();
    }

    @GET
    @Path("/cache/rules/school")
    @Produces(MediaType.TEXT_PLAIN)
    public String getSchoolRules() {
        var rules = cacheManager.getAllSchoolRules();
        StringBuilder sb = new StringBuilder();
        sb.append("School rules: ").append(rules.size()).append("\n");
        rules.forEach(r -> sb.append("  - ").append(r).append("\n"));
        return sb.toString();
    }

    @GET
    @Path("/cache/profiles/active/{email}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getActiveProfiles(
            @PathParam("email") String email,
            @QueryParam("expand") @DefaultValue("true") boolean expand) {

        var profileIds = cacheManager.getActiveProfilesForStudent(email);

        // Automatically record heartbeat if any active profile has tracking enabled
        if (!profileIds.isEmpty()) {
            boolean shouldTrack = profileIds.stream()
                .map(profileId -> cacheManager.getProfile(profileId))
                .filter(profile -> profile != null)
                .anyMatch(profile -> profile.trackingEnabled != null && profile.trackingEnabled);

            if (shouldTrack) {
                trackingManager.recordHeartbeat(email);
            }
        }

        if (!expand) {
            // Return just the IDs (even if empty)
            if (profileIds.isEmpty()) {
                return "{\"email\":\"" + email + "\",\"profileIds\":[]}";
            }
            // Return just the IDs
            StringBuilder sb = new StringBuilder();
            sb.append("{\"email\":\"").append(email).append("\",\"profileIds\":[");
            boolean first = true;
            for (Long profileId : profileIds) {
                if (!first) sb.append(",");
                sb.append(profileId);
                first = false;
            }
            sb.append("]}");
            return sb.toString();
        }

        // Expand mode - return full profile details (default)
        if (profileIds.isEmpty()) {
            return "{\"email\":\"" + email + "\",\"profiles\":[]}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"email\":\"").append(email).append("\",\"profiles\":[");
        boolean first = true;
        for (Long profileId : profileIds) {
            if (!first) sb.append(",");
            var profile = cacheManager.getProfile(profileId);
            if (profile != null) {
                sb.append("{");
                sb.append("\"id\":").append(profile.id).append(",");
                sb.append("\"name\":\"").append(escapeJson(profile.name)).append("\",");
                sb.append("\"teacherId\":").append(profile.teacherId).append(",");
                sb.append("\"schoolId\":").append(profile.schoolId).append(",");
                sb.append("\"isWhitelistUrl\":").append(profile.isWhitelistUrl).append(",");

                // Programs array
                sb.append("\"programs\":");
                if (profile.programs != null && !profile.programs.isEmpty()) {
                    sb.append("[");
                    boolean firstProgram = true;
                    for (String program : profile.programs) {
                        if (!firstProgram) sb.append(",");
                        sb.append("\"").append(escapeJson(program)).append("\"");
                        firstProgram = false;
                    }
                    sb.append("]");
                } else {
                    sb.append("[]");
                }
                sb.append(",");

                // Domains array
                sb.append("\"domains\":");
                if (profile.domains != null && !profile.domains.isEmpty()) {
                    sb.append("[");
                    boolean firstDomain = true;
                    for (String domain : profile.domains) {
                        if (!firstDomain) sb.append(",");
                        sb.append("\"").append(escapeJson(domain)).append("\"");
                        firstDomain = false;
                    }
                    sb.append("]");
                } else {
                    sb.append("[]");
                }
                sb.append(",");

                // Categories array with subcategories and URLs
                sb.append("\"categories\":");
                if (profile.categories != null && !profile.categories.isEmpty()) {
                    sb.append("[");
                    boolean firstCategory = true;
                    for (var category : profile.categories) {
                        if (!firstCategory) sb.append(",");
                        sb.append("{");
                        sb.append("\"id\":").append(category.id).append(",");
                        sb.append("\"name\":\"").append(escapeJson(category.name)).append("\",");

                        // Subcategories array
                        sb.append("\"subcategories\":");
                        if (category.subcategories != null && !category.subcategories.isEmpty()) {
                            sb.append("[");
                            boolean firstSubcat = true;
                            for (var subcat : category.subcategories) {
                                if (!firstSubcat) sb.append(",");
                                sb.append("{");
                                sb.append("\"id\":").append(subcat.id).append(",");
                                sb.append("\"name\":\"").append(escapeJson(subcat.name)).append("\",");

                                // URLs array
                                sb.append("\"urls\":");
                                if (subcat.urls != null && !subcat.urls.isEmpty()) {
                                    sb.append("[");
                                    boolean firstUrl = true;
                                    for (var url : subcat.urls) {
                                        if (!firstUrl) sb.append(",");
                                        sb.append("{");
                                        sb.append("\"id\":").append(url.id).append(",");
                                        sb.append("\"url\":\"").append(escapeJson(url.url)).append("\"");
                                        sb.append("}");
                                        firstUrl = false;
                                    }
                                    sb.append("]");
                                } else {
                                    sb.append("[]");
                                }

                                sb.append("}");
                                firstSubcat = false;
                            }
                            sb.append("]");
                        } else {
                            sb.append("[]");
                        }

                        sb.append("}");
                        firstCategory = false;
                    }
                    sb.append("]");
                } else {
                    sb.append("[]");
                }
                sb.append("}");
            }
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    @GET
    @Path("/cache/cleanup")
    @Produces(MediaType.TEXT_PLAIN)
    public String triggerCleanup() {
        cacheManager.cleanupStaleData();
        return "Cleanup triggered successfully";
    }

    @GET
    @Path("/tracking/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public String trackingStats() {
        var stats = trackingManager.getStats();
        return String.format(
            "{\"activeSessions\":%d,\"activeRuleContexts\":%d,\"indexedStudents\":%d,\"indexedTeachers\":%d,\"indexedSchools\":%d}",
            stats.get("activeSessions"),
            stats.get("activeRuleContexts"),
            stats.get("indexedStudents"),
            stats.get("indexedTeachers"),
            stats.get("indexedSchools")
        );
    }

    @GET
    @Path("/tracking/session/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getSessionTracking(@PathParam("sessionId") Long sessionId) {
        var stats = trackingManager.getSessionTracking(sessionId);
        if (stats.isEmpty()) {
            return "{\"sessionId\":" + sessionId + ",\"students\":[]}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"sessionId\":").append(sessionId).append(",\"students\":[");
        boolean first = true;
        for (var entry : stats.entrySet()) {
            if (!first) sb.append(",");
            var stat = entry.getValue();
            sb.append("{\"email\":\"").append(escapeJson(stat.email)).append("\",");
            sb.append("\"isCurrentlyActive\":").append(stat.isCurrentlyActive).append(",");
            sb.append("\"last3Minutes\":[");
            for (int i = 0; i < stat.last3Minutes.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(stat.last3Minutes.get(i));
            }
            sb.append("],");
            sb.append("\"totalActiveMinutes\":").append(stat.totalActiveMinutes).append(",");
            sb.append("\"totalMinutes\":").append(stat.totalMinutes).append(",");
            sb.append("\"percentage\":").append(stat.percentage).append(",");
            sb.append("\"isActive\":").append(stat.isActive);
            sb.append("}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    @GET
    @Path("/tracking/teacher/{teacherId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getTeacherTracking(@PathParam("teacherId") Long teacherId) {
        var sessionData = trackingManager.getTeacherSessionTracking(teacherId);

        if (sessionData.isEmpty()) {
            return "{\"teacherId\":" + teacherId + ",\"sessions\":[]}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"teacherId\":").append(teacherId).append(",\"sessions\":[");

        boolean firstSession = true;
        for (var sessionEntry : sessionData.entrySet()) {
            if (!firstSession) sb.append(",");
            Long sessionId = sessionEntry.getKey();
            var students = sessionEntry.getValue();

            sb.append("{\"sessionId\":").append(sessionId).append(",\"students\":[");

            boolean firstStudent = true;
            for (var studentEntry : students.entrySet()) {
                if (!firstStudent) sb.append(",");
                var stat = studentEntry.getValue();
                sb.append("{\"email\":\"").append(escapeJson(stat.email)).append("\",");
                sb.append("\"isCurrentlyActive\":").append(stat.isCurrentlyActive).append(",");
                sb.append("\"last3Minutes\":[");
                for (int i = 0; i < stat.last3Minutes.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(stat.last3Minutes.get(i));
                }
                sb.append("],");
                sb.append("\"totalActiveMinutes\":").append(stat.totalActiveMinutes).append(",");
                sb.append("\"totalMinutes\":").append(stat.totalMinutes).append(",");
                sb.append("\"percentage\":").append(stat.percentage).append(",");
                sb.append("\"isActive\":").append(stat.isActive);
                sb.append("}");
                firstStudent = false;
            }

            sb.append("]}");
            firstSession = false;
        }

        sb.append("]}");
        return sb.toString();
    }
}
