package org.concentratee.cache.model;

import java.util.ArrayList;
import java.util.List;

public class UrlCategory {
    public Long id;
    public String name;
    public List<UrlSubcategory> subcategories = new ArrayList<>();

    @Override
    public String toString() {
        return "UrlCategory{id=" + id + ", name=" + name +
               ", subcategories=" + subcategories.size() + "}";
    }
}
