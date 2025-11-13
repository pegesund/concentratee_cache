package org.concentratee.cache.tracking;

import java.util.List;

/**
 * Statistics for a single student's attendance in a session or rule context.
 */
public class AttendanceStats {
    public final String email;
    public final boolean isCurrentlyActive;
    public final List<Integer> last3Minutes;
    public final int totalActiveMinutes;
    public final int totalMinutes;
    public final double percentage;
    public final boolean isActive;  // Based on 80% threshold

    public AttendanceStats(String email, boolean isCurrentlyActive, List<Integer> last3Minutes,
                          int totalActiveMinutes, int totalMinutes, double percentage, boolean isActive) {
        this.email = email;
        this.isCurrentlyActive = isCurrentlyActive;
        this.last3Minutes = last3Minutes;
        this.totalActiveMinutes = totalActiveMinutes;
        this.totalMinutes = totalMinutes;
        this.percentage = percentage;
        this.isActive = isActive;
    }

    @Override
    public String toString() {
        return String.format("AttendanceStats{email='%s', currentlyActive=%b, totalActive=%d/%d, percentage=%.2f%%, isActive=%b}",
            email, isCurrentlyActive, totalActiveMinutes, totalMinutes, percentage, isActive);
    }
}
