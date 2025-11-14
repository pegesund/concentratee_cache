package org.concentratee.cache.model;

import java.util.ArrayList;
import java.util.List;

public class UrlCategory {
    public Long id;
    public String name;
    public Boolean isActive;  // From profiles_categories.is_active
    public List<UrlSubcategory> subcategories = new ArrayList<>();

    @Override
    public String toString() {
        return "UrlCategory{id=" + id + ", name=" + name + ", isActive=" + isActive +
               ", subcategories=" + subcategories.size() + "}";
    }
}
