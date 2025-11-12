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

        if (profileIds.isEmpty()) {
            return "{\"email\":\"" + email + "\",\"profiles\":[]}";
        }

        if (!expand) {
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

        // Expand mode - return full profile details
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
}
