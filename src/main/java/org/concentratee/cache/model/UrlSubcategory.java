package org.concentratee.cache.model;

import java.util.ArrayList;
import java.util.List;

public class UrlSubcategory {
    public Long id;
    public String name;
    public List<CategoryUrl> urls = new ArrayList<>();

    @Override
    public String toString() {
        return "UrlSubcategory{id=" + id + ", name=" + name +
               ", urls=" + urls.size() + "}";
    }
}
