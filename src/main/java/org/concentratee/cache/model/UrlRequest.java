package org.concentratee.cache.model;

public class UrlRequest {
    public Long id;
    public String url;
    public String status;  // pending, accepted, rejected
    public String email;
    public Long teacherId;
    public Long sessionId;
    public String description;
    public Long teacherSessionId;
    public Long urlSubcategoryId;

    @Override
    public String toString() {
        return "UrlRequest{id=" + id + ", url=" + url + ", status=" + status +
               ", teacherSessionId=" + teacherSessionId + "}";
    }
}
