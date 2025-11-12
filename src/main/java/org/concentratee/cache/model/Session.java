package org.concentratee.cache.model;

import java.time.LocalDateTime;

public class Session {
    public Long id;
    public String title;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public Long studentId;
    public Long classId;
    public Long teacherId;
    public Long schoolId;
    public Long teacherSessionId;
    public Integer grade;
    public Long profileId;
    public Boolean isActive;
    public Double percentage;
    public String studentEmail;  // denormalized from student for faster lookup

    // Helper methods
    public boolean isActiveNow() {
        LocalDateTime now = LocalDateTime.now();
        return startTime != null && endTime != null &&
               !now.isBefore(startTime) && !now.isAfter(endTime);
    }

    @Override
    public String toString() {
        return "Session{id=" + id + ", studentId=" + studentId + ", profileId=" + profileId +
               ", grade=" + grade + ", startTime=" + startTime + ", endTime=" + endTime + "}";
    }
}
