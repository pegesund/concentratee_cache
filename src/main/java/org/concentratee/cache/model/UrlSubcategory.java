package org.concentratee.cache.model;

import java.util.ArrayList;
import java.util.List;

public class UrlSubcategory {
    public Long id;
    public String name;
    public Boolean isActive = true;  // Active unless in profile_inactive_subcategories
    public List<CategoryUrl> urls = new ArrayList<>();

    @Override
    public String toString() {
        return "UrlSubcategory{id=" + id + ", name=" + name + ", isActive=" + isActive +
               ", urls=" + urls.size() + "}";
    }
}
