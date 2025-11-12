# Cleanup Strategy

The cache implements a two-layer cleanup strategy to prevent stale data accumulation while maintaining performance.

## Problem: Stale Data Accumulation

Without cleanup, the cache would accumulate stale data over time:

### Sessions
- Loaded: Only sessions where `DATE(start_time) = CURRENT_DATE`
- Problem: A session from 9:00-9:45 stays in cache all day
- Issue: If app runs continuously, yesterday's sessions never removed

### Rules
- Loaded: Only rules where `start_time <= CURRENT_DATE AND end_time >= CURRENT_DATE`
- Problem: A rule expiring tomorrow stays in cache forever
- Issue: Rules expiring mid-day not removed until restart

**Memory Impact**: Without cleanup, cache grows ~30 KB/day for sessions

---

## Solution: Two-Layer Strategy

### Layer 1: Smart Filtering (Immediate Protection)

**Purpose**: Prevent stale data from affecting results

**Implementation**: Filter data when accessed

```java
public List<Session> getSessionsByEmail(String email) {
    List<Session> sessions = sessionsByEmail.get(email);
    if (sessions == null) return Collections.emptyList();

    // Only return TODAY's sessions
    LocalDate today = LocalDate.now();
    return sessions.stream()
        .filter(s -> s.startTime != null &&
                     s.startTime.toLocalDate().equals(today))
        .collect(Collectors.toList());
}
```

**Benefits**:
- Stale data never affects results
- No risk of serving old data
- Works immediately without waiting for cleanup

**Code Location**: `CacheManager.getSessionsByEmail()` (lines 638-650)

---

### Layer 2: Scheduled Cleanup (Memory Management)

**Purpose**: Free memory by removing stale data

**Schedule**: Every 6 hours, starting 1 hour after startup

**Configuration**:
```java
@Scheduled(every = "6h", delay = 1, delayUnit = TimeUnit.HOURS)
void cleanupStaleData()
```

#### Cleanup Process

**1. Remove Old Sessions**
```java
LocalDate today = LocalDate.now();
for (Session session : sessionsById.values()) {
    if (session.startTime.toLocalDate().isBefore(today)) {
        // Remove from primary map
        sessionsById.remove(session.id);

        // Remove from indexes
        removeSessionFromIndexes(session.id, session);
    }
}
```

**Criteria**: `session.startTime < today`

**Example**: On Nov 13, removes all sessions from Nov 12 or earlier

---

**2. Remove Expired Rules**
```java
LocalDateTime now = LocalDateTime.now();
for (Rule rule : rulesById.values()) {
    if (rule.endTime != null && rule.endTime.isBefore(now)) {
        // Remove from primary map
        rulesById.remove(rule.id);

        // Remove from indexes
        removeRuleFromIndexes(rule.id, rule);
    }
}
```

**Criteria**: `rule.endTime < now`

**Example**: On Nov 12 22:00, removes rule with endTime = Nov 12 20:00

---

#### Index Maintenance

**removeSessionFromIndexes()**:
```java
// Remove from email index
if (session.studentEmail != null) {
    sessionsByEmail.computeIfPresent(session.studentEmail, (email, sessions) -> {
        sessions.removeIf(s -> s.id.equals(sessionId));
        return sessions.isEmpty() ? null : sessions;  // Remove key if empty
    });
}

// Remove from profile index
if (session.profileId != null) {
    sessionsByProfile.computeIfPresent(session.profileId, (profileId, sessions) -> {
        sessions.removeIf(s -> s.id.equals(sessionId));
        return sessions.isEmpty() ? null : sessions;
    });
}
```

**removeRuleFromIndexes()**:
```java
if (rule.scope != null && rule.scopeValue != null) {
    rulesByScopeAndValue.computeIfPresent(rule.scope, (scope, valueMap) -> {
        valueMap.computeIfPresent(rule.scopeValue, (value, rules) -> {
            rules.removeIf(r -> r.id.equals(ruleId));
            return rules.isEmpty() ? null : rules;  // Remove if empty
        });
        return valueMap.isEmpty() ? null : valueMap;  // Remove scope if empty
    });
}
```

**Performance**: O(1) per item removed (no full index rebuild)

**Code Location**:
- `CacheManager.cleanupStaleData()` (lines 705-741)
- `CacheManager.removeSessionFromIndexes()` (lines 673-689)
- `CacheManager.removeRuleFromIndexes()` (lines 691-702)

---

## Cleanup Logging

```
🧹 Starting scheduled cleanup of stale data...
✅ Cleanup completed: removed 0 old sessions and 0 expired rules
```

**Information Logged**:
- Start time
- Number of sessions removed
- Number of rules removed

---

## Manual Cleanup Trigger

For testing or emergency cleanup:

**Endpoint**: `GET /cache/cleanup`

**Response**: `"Cleanup triggered successfully"`

**Use Cases**:
- Testing cleanup logic
- Manual memory management
- Post-database maintenance

---

## Timeline Examples

### Scenario 1: Sessions Aging Out

**Nov 12, 00:00** - Startup
- Load 138 sessions for Nov 12

**Nov 12, 01:00** - First cleanup
- Check all sessions
- All have startTime = Nov 12
- Result: 0 sessions removed

**Nov 12, 07:00, 13:00, 19:00** - Subsequent cleanups
- Result: 0 sessions removed (all current)

**Nov 13, 00:00** - App still running
- Old query still in code: `WHERE DATE(start_time) = CURRENT_DATE`
- LISTEN/NOTIFY adds new Nov 13 sessions
- Nov 12 sessions still in cache

**Nov 13, 01:00** - Cleanup runs
- Check all sessions
- 138 sessions have startTime = Nov 12 (before today)
- **Result: 138 old sessions removed** ✅
- Memory freed: ~30 KB

---

### Scenario 2: Rules Expiring Mid-Day

**Nov 12, 08:00** - Startup
- Load rule: startTime=Nov 11, endTime=Nov 12 20:00

**Nov 12, 09:00, 15:00** - Cleanups
- rule.endTime = Nov 12 20:00 (still in future)
- Result: 0 rules removed

**Nov 12, 21:00** - Cleanup runs
- rule.endTime = Nov 12 20:00 (now in past)
- **Result: 1 rule removed** ✅

---

## Alternative Strategies Considered

### Option 1: Scheduled Daily Reload
**Approach**: Clear cache and reload at midnight

**Pros**: Simple, guarantees fresh data
**Cons**: Brief downtime, unnecessary for profiles/rules

**Not Chosen**: Too disruptive

---

### Option 2: TTL-based Cache (Caffeine)
**Approach**: Replace ConcurrentHashMap with Caffeine cache with TTL

**Pros**: Automatic expiration, built-in cleanup
**Cons**: Major refactor, complex expiration rules per data type

**Not Chosen**: Too complex for current needs

---

### Option 3: No Cleanup
**Approach**: Rely only on smart filtering

**Pros**: Simplest implementation
**Cons**: Memory grows unbounded

**Not Chosen**: Memory leak risk

---

## Performance Characteristics

**Cleanup Duration**: ~10-50ms for typical workload (138 sessions, 10 rules)

**Memory Freed Per Day**: ~30 KB (138 sessions)

**CPU Impact**: Minimal (runs every 6 hours, not on hot path)

**Thread**: Runs on `executor-thread` (not blocking event loop)

---

## Monitoring

Check cleanup effectiveness:

```bash
# Watch cleanup logs
curl http://localhost:8082/cache/cleanup

# Check cache stats
curl http://localhost:8082/cache/stats
```

Expected after cleanup:
- `sessionsById` should match current day only
- `rulesById` should contain only active rules
