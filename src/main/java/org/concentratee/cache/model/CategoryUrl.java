package org.concentratee.cache.model;

public class CategoryUrl {
    public Long id;
    public String url;
    public Boolean isActive = true;  // Active unless in profile_inactive_urls

    @Override
    public String toString() {
        return "CategoryUrl{id=" + id + ", url=" + url + ", isActive=" + isActive + "}";
    }
}
