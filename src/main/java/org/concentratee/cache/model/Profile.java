package org.concentratee.cache.model;

import java.util.ArrayList;
import java.util.List;

public class Profile {
    public Long id;
    public String name;
    public List<String> domains;  // Parsed from JSONB
    public List<String> programs; // From join table
    public List<UrlCategory> categories; // From profiles_categories with subcategories and URLs
    public Long teacherId;
    public Long schoolId;
    public Boolean isWhitelistUrl;
    public Boolean trackingEnabled;  // TODO: Load from database once tracking_enabled column is added

    public Profile() {
        this.domains = new ArrayList<>();
        this.programs = new ArrayList<>();
        this.categories = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "Profile{id=" + id + ", name=" + name + ", domains=" + domains + ", categories=" + categories.size() + "}";
    }
}
