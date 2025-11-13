# Appearance Tracking System

Real-time minute-level tracking of student program activity with automatic persistence to database.

## Overview

The tracking system monitors student "heartbeats" (program activity signals) to calculate attendance statistics for sessions and rules. It provides:

- **Minute-level granularity**: Tracks activity per minute with binary counters
- **Thread-safe operations**: Concurrent tracking across multiple students
- **Automatic persistence**: Session statistics saved to database on cleanup
- **Memory efficient**: Rolling 4-minute history window per student

## Architecture

### Component Hierarchy

```
TrackingManager
├── SessionTracker (per active session)
│   └── StudentMinuteTracker (per student in session)
└── RuleTracker (per rule context)
    └── StudentMinuteTracker (per student matching rule)
```

### Key Classes

1. **TrackingManager** (`TrackingManager.java:26`)
   - Manages all active trackers
   - Routes heartbeats to appropriate trackers
   - Schedules cleanup and rotation
   - Persists session data to database

2. **SessionTracker** (`SessionTracker.java:15`)
   - Tracks all students in a single session
   - Calculates session duration from start/end times
   - Aggregates per-student statistics

3. **RuleTracker** (`RuleTracker.java`)
   - Tracks students matching a rule (without active session)
   - Groups by scope context (school/grade/class)
   - Auto-cleanup after 30 minutes of inactivity

4. **StudentMinuteTracker** (`StudentMinuteTracker.java:12`)
   - Tracks individual student activity
   - Maintains rolling 4-minute history
   - Calculates attendance percentage

## How Counting Works

### 1. Heartbeat Recording

When a heartbeat is received (via API with `?track=true`):

```java
// TrackingManager.java:54
public void recordHeartbeat(String studentEmail) {
    // 1. Get student from cache
    Student student = cacheManager.getStudentByEmail(studentEmail);

    // 2. Find active sessions
    Set<Session> activeSessions = cacheManager.getSessionsByEmail(studentEmail)
        .filter(s -> s.isActiveNow());

    // 3. Record in session trackers
    activeSessions.forEach(session -> {
        SessionTracker tracker = sessionTrackers.computeIfAbsent(session.id, ...);
        tracker.recordHeartbeat(studentEmail);  // Increments counter
    });

    // 4. Record in rule trackers (if no session with same profile)
    Set<Rule> activeRules = cacheManager.getActiveRulesForStudent(student);
    activeRules.forEach(rule -> {
        RuleTracker tracker = ruleTrackers.computeIfAbsent(contextKey, ...);
        tracker.recordHeartbeat(studentEmail);  // Increments counter
    });
}
```

**Per-Minute Counter**: Each call to `recordHeartbeat()` increments `currentMinuteCounter` in `StudentMinuteTracker`:

```java
// StudentMinuteTracker.java:22
public void recordHeartbeat() {
    currentMinuteCounter.incrementAndGet();  // Thread-safe increment
}
```

Multiple heartbeats in the same minute are allowed but only count as **1 active minute** after rotation.

### 2. Minute Rotation

Every minute at `:00` boundary (scheduled with `@Scheduled(cron = "0 * * * * ?")`):

```java
// StudentMinuteTracker.java:31
public void rotateMinute() {
    int count = currentMinuteCounter.getAndSet(0);  // Get counter and reset
    int binaryValue = (count > 0) ? 1 : 0;          // Convert to binary

    history.addFirst(binaryValue);                   // Add to history
    if (history.size() > 4) {
        history.removeLast();                        // Keep only last 4 minutes
    }

    totalActiveMinutes += binaryValue;               // Accumulate total
}
```

**Example Timeline**:

```
Minute 1: recordHeartbeat() called 3 times → counter = 3
Rotation: 3 > 0 → history = [1], totalActiveMinutes = 1

Minute 2: recordHeartbeat() called 5 times → counter = 5
Rotation: 5 > 0 → history = [1, 1], totalActiveMinutes = 2

Minute 3: No heartbeat → counter = 0
Rotation: 0 = 0 → history = [0, 1, 1], totalActiveMinutes = 2

Minute 4: recordHeartbeat() called 1 time → counter = 1
Rotation: 1 > 0 → history = [1, 0, 1, 1], totalActiveMinutes = 3
```

### 3. Percentage Calculation

Attendance percentage is calculated based on **total session duration**:

```java
// StudentMinuteTracker.java:93
public double calculatePercentage(int totalMinutes) {
    if (totalMinutes <= 0) return 0.0;
    return Math.round((totalActiveMinutes / (double) totalMinutes) * 100.0 * 100.0) / 100.0;
}
```

**Important**: `totalMinutes` comes from session duration calculated as:

```java
// SessionTracker.java:27
this.totalMinutes = (int) ChronoUnit.MINUTES.between(sessionStart, sessionEnd);
```

**Example**:
- Session: 10:00 - 11:00 (60 minutes)
- Student active: 45 minutes
- Percentage: `(45 / 60) * 100 = 75.00%`

**Edge Case**: A session shorter than 1 minute (e.g., 30 seconds) will have `totalMinutes = 0` and percentage will return `0.0` even if heartbeats were recorded.

### 4. Active Status Threshold

A student is considered "active" if attendance >= 80%:

```java
// StudentMinuteTracker.java:103
public boolean calculateIsActive(int totalMinutes) {
    if (totalMinutes <= 0) return false;
    return totalActiveMinutes > (0.8 * totalMinutes);
}
```

**Example**:
- 60-minute session: Need > 48 minutes to be "active"
- 30-minute session: Need > 24 minutes to be "active"
- 10-minute session: Need > 8 minutes to be "active"

## Database Persistence

### When Persistence Happens

Session tracking data is persisted when:
1. Session ends (checked every 5 minutes by `@Scheduled(cron = "0 */5 * * * ?")`)
2. Cleanup detects `hasEnded() == true`
3. Tracker is removed from memory

### What Gets Persisted

The `sessions` table stores **aggregate statistics** per session (NOT per student):

```sql
-- Sessions table columns
is_active   BOOLEAN    -- Is the session active? (80% of students must be active)
percentage  DOUBLE     -- Average attendance percentage across all students
```

**Persistence Logic** (`TrackingManager.java:232`):

```java
private void persistSessionTracking(Long sessionId, SessionTracker tracker) {
    Map<String, AttendanceStats> stats = tracker.calculateStats();

    if (stats.isEmpty()) {
        return; // No students tracked
    }

    // 1. Calculate average percentage across ALL students
    double totalPercentage = stats.values().stream()
        .mapToDouble(s -> s.percentage)
        .average()
        .orElse(0.0);

    // 2. Calculate session-level is_active
    //    (true if 80% of students are individually active)
    long activeStudents = stats.values().stream()
        .filter(s -> s.isActive)
        .count();
    boolean sessionIsActive = (activeStudents / (double) stats.size()) > 0.8;

    // 3. Persist to database
    String query = String.format(
        "UPDATE sessions SET is_active = %s, percentage = %s WHERE id = %d",
        sessionIsActive, totalPercentage, sessionId
    );

    pgPool.query(query).execute().subscribe().with(
        success -> LOG.debug("Persisted tracking for session " + sessionId),
        failure -> LOG.errorf("Failed to persist tracking: %s", failure.getMessage())
    );
}
```

### Persistence Examples

**Example 1: Single student session**
```
Session ID: 123
Duration: 30 minutes
Student A: 25 active minutes (83.33%)

Persisted:
  is_active = true     (83.33% > 80%)
  percentage = 83.33   (average of [83.33])
```

**Example 2: Multi-student session**
```
Session ID: 456
Duration: 60 minutes
Student A: 50 active minutes (83.33%) → isActive = true
Student B: 45 active minutes (75.00%) → isActive = false
Student C: 55 active minutes (91.67%) → isActive = true

Persisted:
  is_active = false    (2/3 = 66.7% students active, need > 80%)
  percentage = 83.33   (average of [83.33, 75.00, 91.67])
```

**Example 3: Low attendance**
```
Session ID: 789
Duration: 45 minutes
Student A: 10 active minutes (22.22%) → isActive = false
Student B: 15 active minutes (33.33%) → isActive = false

Persisted:
  is_active = false    (0/2 = 0% students active)
  percentage = 27.78   (average of [22.22, 33.33])
```

## Scheduled Operations

### Minute Rotation
**Schedule**: Every minute at `:00` seconds
**Cron**: `0 * * * * ?`
**Action**: Rotates all student trackers in all active sessions and rule contexts

```java
@Scheduled(cron = "0 * * * * ?")
void rotateMinute() {
    sessionTrackers.values().forEach(SessionTracker::rotateMinute);
    ruleTrackers.values().forEach(RuleTracker::rotateMinute);
}
```

### Session Cleanup
**Schedule**: Every 5 minutes
**Cron**: `0 */5 * * * ?`
**Action**: Persists and removes ended sessions

```java
@Scheduled(cron = "0 */5 * * * ?")
void cleanupEndedSessions() {
    List<Long> endedSessionIds = sessionTrackers.entrySet().stream()
        .filter(e -> e.getValue().hasEnded())
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    endedSessionIds.forEach(sessionId -> {
        SessionTracker tracker = sessionTrackers.get(sessionId);
        persistSessionTracking(sessionId, tracker);  // Save to DB
        sessionTrackers.remove(sessionId);           // Remove from memory
        removeSessionFromIndexes(sessionId);         // Clean indexes
    });
}
```

### Rule Context Cleanup
**Schedule**: Every 10 minutes
**Cron**: `0 */10 * * * ?`
**Action**: Removes stale rule contexts (no heartbeat for 30 minutes)

```java
@Scheduled(cron = "0 */10 * * * ?")
void cleanupStaleRuleContexts() {
    List<String> staleContextKeys = ruleTrackers.entrySet().stream()
        .filter(e -> e.getValue().isStale(30))  // 30 minutes threshold
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    staleContextKeys.forEach(contextKey -> {
        ruleTrackers.remove(contextKey);
        removeRuleContextFromIndexes(contextKey, schoolId);
    });
}
```

## API Integration

### Recording Heartbeats

Heartbeats are recorded via the existing profile API with `track=true` parameter:

```bash
GET /cache/profiles/active/{email}?track=true
```

**Process**:
1. API endpoint calls `trackingManager.recordHeartbeat(email)`
2. TrackingManager finds active sessions and rules
3. Creates/updates trackers as needed
4. Records heartbeat in each applicable tracker

### Querying Tracking Data

**Session Tracking**:
```bash
GET /tracking/session/{sessionId}
```

Returns real-time statistics for all students in the session.

**Teacher Sessions**:
```bash
GET /tracking/teacher/{teacherId}
```

Returns tracking data for all sessions owned by the teacher.

**Tracking Stats**:
```bash
GET /tracking/stats
```

Returns system-wide statistics:
```json
{
  "activeSessions": 5,
  "activeRuleContexts": 2,
  "indexedStudents": 12,
  "indexedTeachers": 3,
  "indexedSchools": 1
}
```

## Memory Usage

### Per-Student Memory

Each `StudentMinuteTracker`:
```
currentMinuteCounter: 4 bytes (AtomicInteger)
history: 4 integers × 4 bytes = 16 bytes (rolling window)
totalActiveMinutes: 4 bytes (int)
Total per student: ~24 bytes + object overhead ≈ 50 bytes
```

### Typical Session

```
Session with 30 students:
  SessionTracker: ~100 bytes
  30 × StudentMinuteTracker: 30 × 50 = 1,500 bytes
Total per session: ~1.6 KB
```

### System Capacity

```
100 active sessions × 30 students each = 3,000 students
Memory: 100 × 1.6 KB = 160 KB
```

Very memory efficient due to:
- Binary counters (0 or 1) in history
- Fixed 4-minute rolling window
- Automatic cleanup of ended sessions

## Thread Safety

All tracking operations are thread-safe:

1. **TrackingManager maps**: `ConcurrentHashMap` for tracker storage
2. **StudentMinuteTracker counter**: `AtomicInteger` for heartbeat counting
3. **History deque**: `ConcurrentLinkedDeque` for minute history
4. **Indexes**: `ConcurrentHashMap.newKeySet()` for set operations

Multiple heartbeats can be recorded concurrently without data corruption or lost updates.

## Testing

Comprehensive test coverage in:
- `StudentMinuteTrackerTest.java` - 15 unit tests
- `SessionTrackerTest.java` - 10 unit tests
- `RuleTrackerTest.java` - 10 unit tests
- `TrackingManagerTest.java` - 10 unit tests
- `TrackingIntegrationTest.java` - 8 integration tests
- `RealWorldTrackingTest.java` - 8 critical scenario tests

**Total**: 61 tests covering:
- Heartbeat recording
- Minute rotation
- Percentage calculation
- Active status thresholds
- Database persistence
- Concurrent operations
- Rule-only tracking
- Session priority over rules
- Cleanup operations
