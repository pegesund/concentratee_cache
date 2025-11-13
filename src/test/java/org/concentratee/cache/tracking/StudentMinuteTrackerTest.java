package org.concentratee.cache.tracking;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for StudentMinuteTracker.
 * Tests minute-level tracking, rotation, history management, and thread safety.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudentMinuteTrackerTest {

    @Test
    @Order(1)
    @DisplayName("Should initialize with zero counters")
    void testInitialization() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        assertEquals(0, tracker.getCurrentMinuteCounter());
        assertEquals(0, tracker.getTotalActiveMinutes());
        assertFalse(tracker.isCurrentlyActive());
        assertTrue(tracker.getHistory().isEmpty());
        assertTrue(tracker.getLast3Minutes().isEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("Should record single heartbeat")
    void testSingleHeartbeat() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        tracker.recordHeartbeat();

        assertEquals(1, tracker.getCurrentMinuteCounter());
        assertEquals(0, tracker.getTotalActiveMinutes()); // Not rotated yet
    }

    @Test
    @Order(3)
    @DisplayName("Should record multiple heartbeats in same minute")
    void testMultipleHeartbeats() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        tracker.recordHeartbeat();
        tracker.recordHeartbeat();
        tracker.recordHeartbeat();

        assertEquals(3, tracker.getCurrentMinuteCounter());
        assertEquals(0, tracker.getTotalActiveMinutes());
    }

    @Test
    @Order(4)
    @DisplayName("Should rotate minute correctly with heartbeats")
    void testRotateMinuteWithHeartbeats() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        tracker.recordHeartbeat();
        tracker.recordHeartbeat();
        tracker.rotateMinute();

        assertEquals(0, tracker.getCurrentMinuteCounter()); // Reset
        assertEquals(1, tracker.getTotalActiveMinutes()); // 1 active minute
        assertEquals(List.of(1), tracker.getHistory());
        assertTrue(tracker.isCurrentlyActive()); // Last minute was active
    }

    @Test
    @Order(5)
    @DisplayName("Should rotate minute correctly without heartbeats")
    void testRotateMinuteWithoutHeartbeats() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        tracker.rotateMinute(); // No heartbeats

        assertEquals(0, tracker.getCurrentMinuteCounter());
        assertEquals(0, tracker.getTotalActiveMinutes());
        assertEquals(List.of(0), tracker.getHistory());
        assertFalse(tracker.isCurrentlyActive()); // Last minute was inactive
    }

    @Test
    @Order(6)
    @DisplayName("Should maintain rolling history of 4 minutes")
    void testRollingHistory() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        // Minute 1: active
        tracker.recordHeartbeat();
        tracker.rotateMinute();

        // Minute 2: inactive
        tracker.rotateMinute();

        // Minute 3: active
        tracker.recordHeartbeat();
        tracker.rotateMinute();

        // Minute 4: active
        tracker.recordHeartbeat();
        tracker.rotateMinute();

        // Minute 5: inactive (should drop minute 1)
        tracker.rotateMinute();

        List<Integer> history = tracker.getHistory();
        assertEquals(4, history.size()); // Max 4 values
        assertEquals(List.of(0, 1, 1, 0), history); // [min5, min4, min3, min2] - oldest dropped
        assertEquals(3, tracker.getTotalActiveMinutes()); // 3 out of 5 were active
    }

    @Test
    @Order(7)
    @DisplayName("Should return last 3 minutes correctly")
    void testLast3Minutes() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        // Minute 1: active
        tracker.recordHeartbeat();
        tracker.rotateMinute();

        // Minute 2: inactive
        tracker.rotateMinute();

        // Minute 3: active
        tracker.recordHeartbeat();
        tracker.rotateMinute();

        // Minute 4: active (current)
        tracker.recordHeartbeat();
        tracker.rotateMinute();

        List<Integer> last3 = tracker.getLast3Minutes();
        assertEquals(3, last3.size());
        assertEquals(List.of(1, 0, 1), last3); // [min3, min2, min1] - excludes current (min4)
    }

    @Test
    @Order(8)
    @DisplayName("Should calculate percentage correctly")
    void testCalculatePercentage() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        // Simulate 8 out of 10 minutes active
        for (int i = 0; i < 8; i++) {
            tracker.recordHeartbeat();
            tracker.rotateMinute();
        }
        for (int i = 0; i < 2; i++) {
            tracker.rotateMinute(); // 2 inactive minutes
        }

        double percentage = tracker.calculatePercentage(10);
        assertEquals(80.0, percentage, 0.01);
    }

    @Test
    @Order(9)
    @DisplayName("Should calculate isActive correctly at 80% threshold")
    void testCalculateIsActive() {
        StudentMinuteTracker tracker1 = new StudentMinuteTracker();
        StudentMinuteTracker tracker2 = new StudentMinuteTracker();
        StudentMinuteTracker tracker3 = new StudentMinuteTracker();

        // Tracker 1: 9/10 = 90% (above threshold)
        for (int i = 0; i < 9; i++) {
            tracker1.recordHeartbeat();
            tracker1.rotateMinute();
        }
        tracker1.rotateMinute(); // 1 inactive
        assertTrue(tracker1.calculateIsActive(10));

        // Tracker 2: 8/10 = 80% (at threshold, should be false per Elixir logic > 0.8)
        for (int i = 0; i < 8; i++) {
            tracker2.recordHeartbeat();
            tracker2.rotateMinute();
        }
        for (int i = 0; i < 2; i++) {
            tracker2.rotateMinute(); // 2 inactive
        }
        assertFalse(tracker2.calculateIsActive(10)); // Must be > 80%

        // Tracker 3: 5/10 = 50% (below threshold)
        for (int i = 0; i < 5; i++) {
            tracker3.recordHeartbeat();
            tracker3.rotateMinute();
        }
        for (int i = 0; i < 5; i++) {
            tracker3.rotateMinute(); // 5 inactive
        }
        assertFalse(tracker3.calculateIsActive(10));
    }

    @Test
    @Order(10)
    @DisplayName("Should handle edge case: zero total minutes")
    void testZeroTotalMinutes() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        tracker.recordHeartbeat();
        tracker.rotateMinute();

        assertEquals(0.0, tracker.calculatePercentage(0));
        assertFalse(tracker.calculateIsActive(0));
    }

    @Test
    @Order(11)
    @DisplayName("Should be thread-safe with concurrent heartbeats")
    void testThreadSafety() throws InterruptedException {
        StudentMinuteTracker tracker = new StudentMinuteTracker();
        int threadCount = 10;
        int heartbeatsPerThread = 100;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < heartbeatsPerThread; j++) {
                    tracker.recordHeartbeat();
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount * heartbeatsPerThread, tracker.getCurrentMinuteCounter());
    }

    @Test
    @Order(12)
    @DisplayName("Should handle complex scenario: 30-minute session")
    void testComplexScenario() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        // Simulate 30-minute session with sporadic activity
        // Active for first 10 minutes
        for (int i = 0; i < 10; i++) {
            tracker.recordHeartbeat();
            tracker.rotateMinute();
        }

        // Inactive for next 5 minutes
        for (int i = 0; i < 5; i++) {
            tracker.rotateMinute();
        }

        // Active for next 10 minutes
        for (int i = 0; i < 10; i++) {
            tracker.recordHeartbeat();
            tracker.rotateMinute();
        }

        // Inactive for last 5 minutes
        for (int i = 0; i < 5; i++) {
            tracker.rotateMinute();
        }

        // Verify
        assertEquals(20, tracker.getTotalActiveMinutes()); // 20 out of 30
        assertEquals(66.67, tracker.calculatePercentage(30), 0.01);
        assertFalse(tracker.calculateIsActive(30)); // 66.67% < 80%
        assertFalse(tracker.isCurrentlyActive()); // Last minute was inactive
    }

    @Test
    @Order(13)
    @DisplayName("Should handle rapid rotation scenario")
    void testRapidRotation() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        // Rapid rotation with alternating activity
        for (int i = 0; i < 100; i++) {
            if (i % 2 == 0) {
                tracker.recordHeartbeat();
            }
            tracker.rotateMinute();
        }

        assertEquals(50, tracker.getTotalActiveMinutes());
        assertEquals(50.0, tracker.calculatePercentage(100), 0.01);
        assertTrue(tracker.getHistory().size() <= 4); // Rolling window maintained
    }

    @Test
    @Order(14)
    @DisplayName("Should handle single heartbeat per minute correctly")
    void testSingleHeartbeatPerMinute() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        for (int i = 0; i < 5; i++) {
            tracker.recordHeartbeat(); // Just 1 heartbeat per minute
            tracker.rotateMinute();
        }

        assertEquals(5, tracker.getTotalActiveMinutes());
        assertEquals(100.0, tracker.calculatePercentage(5), 0.01);
        assertTrue(tracker.calculateIsActive(5));
    }

    @Test
    @Order(15)
    @DisplayName("Should handle high-frequency heartbeats in single minute")
    void testHighFrequencyHeartbeats() {
        StudentMinuteTracker tracker = new StudentMinuteTracker();

        // 1000 heartbeats in one minute
        for (int i = 0; i < 1000; i++) {
            tracker.recordHeartbeat();
        }

        assertEquals(1000, tracker.getCurrentMinuteCounter());

        tracker.rotateMinute();

        // Should convert to binary 1 (active)
        assertEquals(1, tracker.getTotalActiveMinutes());
        assertEquals(List.of(1), tracker.getHistory());
    }
}
