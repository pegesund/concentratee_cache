package org.concentratee.cache.model;

import java.util.List;

public class Profile {
    public Long id;
    public String name;
    public List<String> domains;  // Parsed from JSONB
    public List<String> programs; // From join table
    public Long teacherId;
    public Long schoolId;
    public Boolean isWhitelistUrl;

    @Override
    public String toString() {
        return "Profile{id=" + id + ", name=" + name + ", domains=" + domains + "}";
    }
}
