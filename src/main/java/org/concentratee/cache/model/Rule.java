package org.concentratee.cache.model;

import java.time.LocalDateTime;

public class Rule {
    public Long id;
    public String scope;  // "Student", "School", "Grade"
    public String scopeValue;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public Long profileId;

    // Helper method to check if rule is active
    public boolean isActiveNow() {
        LocalDateTime now = LocalDateTime.now();
        return startTime != null && endTime != null &&
               !now.isBefore(startTime) && !now.isAfter(endTime);
    }

    // Helper method to check if rule is active at a specific time
    public boolean isActiveNow(LocalDateTime time) {
        return startTime != null && endTime != null &&
               !time.isBefore(startTime) && !time.isAfter(endTime);
    }

    @Override
    public String toString() {
        return "Rule{id=" + id + ", scope=" + scope + ", scopeValue=" + scopeValue +
               ", profileId=" + profileId + "}";
    }
}
