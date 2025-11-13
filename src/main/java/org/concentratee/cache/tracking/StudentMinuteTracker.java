package org.concentratee.cache.tracking;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks appearance/heartbeat for a single student in a session or rule context.
 * Thread-safe minute-level tracking with rolling history.
 */
public class StudentMinuteTracker {

    private final AtomicInteger currentMinuteCounter = new AtomicInteger(0);
    private final ConcurrentLinkedDeque<Integer> history = new ConcurrentLinkedDeque<>();
    private int totalActiveMinutes = 0;

    /**
     * Record a heartbeat (student program is running).
     * Thread-safe, can be called multiple times per minute.
     */
    public void recordHeartbeat() {
        currentMinuteCounter.incrementAndGet();
    }

    /**
     * Rotate to next minute boundary.
     * Convert current minute counter to binary (0 or 1) and add to history.
     * Reset current counter for new minute.
     */
    public void rotateMinute() {
        int count = currentMinuteCounter.getAndSet(0);
        int binaryValue = (count > 0) ? 1 : 0;

        history.addFirst(binaryValue);
        if (history.size() > 4) {
            history.removeLast();
        }

        totalActiveMinutes += binaryValue;
    }

    /**
     * Is the student currently active (last minute had heartbeat)?
     */
    public boolean isCurrentlyActive() {
        return !history.isEmpty() && history.peekFirst() == 1;
    }

    /**
     * Get last 3 minutes for UI indicators (excluding current minute).
     * Returns [minute-1, minute-2, minute-3] as binary values.
     */
    public List<Integer> getLast3Minutes() {
        List<Integer> result = new ArrayList<>();
        int count = 0;
        for (Integer value : history) {
            if (count > 0 && count <= 3) {  // Skip first (current), take next 3
                result.add(value);
            }
            count++;
            if (count > 3) break;
        }
        return result;
    }

    /**
     * Get total number of active minutes recorded.
     */
    public int getTotalActiveMinutes() {
        return totalActiveMinutes;
    }

    /**
     * Get current minute counter value (for testing).
     */
    public int getCurrentMinuteCounter() {
        return currentMinuteCounter.get();
    }

    /**
     * Get full history (for testing).
     */
    public List<Integer> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Calculate percentage of active minutes out of total.
     * @param totalMinutes Total minutes in session/context
     * @return Percentage (0.0 to 100.0)
     */
    public double calculatePercentage(int totalMinutes) {
        if (totalMinutes <= 0) return 0.0;
        return Math.round((totalActiveMinutes / (double) totalMinutes) * 100.0 * 100.0) / 100.0;
    }

    /**
     * Calculate is_active based on 80% threshold.
     * @param totalMinutes Total minutes in session/context
     * @return true if active >= 80%
     */
    public boolean calculateIsActive(int totalMinutes) {
        if (totalMinutes <= 0) return false;
        return totalActiveMinutes > (0.8 * totalMinutes);
    }
}
