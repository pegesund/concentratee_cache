package org.concentratee.cache.model;

/**
 * Simple student model for tracking.
 */
public class Student {
    public Long id;
    public String email;
    public Long schoolId;
    public Integer grade;
    public Long classId;

    public Student(Long id, String email, Long schoolId) {
        this.id = id;
        this.email = email;
        this.schoolId = schoolId;
    }

    @Override
    public String toString() {
        return String.format("Student{id=%d, email='%s', schoolId=%d}", id, email, schoolId);
    }
}
