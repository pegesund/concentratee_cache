# Cache Architecture

High-level overview of the Concentratee cache system design and data flow.

## System Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Elixir Application                      │
│                   (Future Integration)                      │
└────────────────────────┬────────────────────────────────────┘
                         │ REST API
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                  Java Cache Service (Quarkus)               │
│  ┌──────────────────────────────────────────────────────┐  │
│  │               CacheManager (Singleton)               │  │
│  │                                                      │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐   │  │
│  │  │ studentsBy │  │ profilesBy │  │ rulesById  │   │  │
│  │  │    Id      │  │     Id     │  │            │   │  │
│  │  └────────────┘  └────────────┘  └────────────┘   │  │
│  │                                                      │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐   │  │
│  │  │sessionsById│  │sessionsByE │  │sessionsByP │   │  │
│  │  │            │  │    mail    │  │  rofile    │   │  │
│  │  └────────────┘  └────────────┘  └────────────┘   │  │
│  │                                                      │  │
│  │  ┌─────────────────────────────────────────────┐   │  │
│  │  │     rulesByScopeAndValue (Compound)         │   │  │
│  │  └─────────────────────────────────────────────┘   │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                   │
│                         │ Query                             │
│                         ▼                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │      Vert.x Reactive PostgreSQL Client               │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                   │
│                         │ LISTEN/NOTIFY                     │
│                         ▼                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         PgSubscriber (Separate Connection)           │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    PostgreSQL Database                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ students │  │ profiles │  │  rules   │  │ sessions │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │             │              │             │          │
│  ┌────▼─────────────▼──────────────▼─────────────▼──────┐  │
│  │              Triggers (AFTER INSERT/UPDATE/DELETE)    │  │
│  └────┬─────────────┬──────────────┬─────────────┬──────┘  │
│       │             │              │             │          │
│  ┌────▼─────────────▼──────────────▼─────────────▼──────┐  │
│  │    NOTIFY: students_changes, profiles_changes, ...    │  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## Data Flow

### 1. Startup Loading

```
Application Start
    │
    ▼
Load Students (Required First)
    │
    ├───────────────────┬───────────────────┐
    ▼                   ▼                   ▼
Load Profiles    Load Rules          Load Sessions
    │                   │                   │
    └───────────────────┴───────────────────┘
                        │
                        ▼
            Build Derived Indexes
                        │
        ├───────────────┼───────────────────┐
        ▼               ▼                   ▼
sessionsByEmail  sessionsByProfile  rulesByScopeAndValue
        │               │                   │
        └───────────────┴───────────────────┘
                        │
                        ▼
            Setup LISTEN/NOTIFY
                        │
                        ▼
                  Cache Ready ✅
```

**Time**: ~1-2 seconds for typical dataset (174 students, 138 sessions)

---

### 2. Real-time Updates

```
Database Change (INSERT/UPDATE/DELETE)
    │
    ▼
Trigger Executes
    │
    ▼
NOTIFY with JSON payload
    │
    ▼
PgSubscriber receives notification
    │
    ▼
CacheManager handler invoked
    │
    ├─ DELETE: Remove from cache
    │
    └─ INSERT/UPDATE:
        │
        ▼
    Reload from database
        │
        ▼
    Update primary hash map
        │
        ▼
    Update derived indexes
        │
        ▼
    Cache synchronized ✅
```

**Latency**: ~50-100ms from database change to cache update

---

### 3. Query Flow

```
Client Request
    │
    ▼
REST Endpoint (Main.java)
    │
    ▼
CacheManager public method
    │
    ├─ Simple lookup: O(1)
    │   └─ hashMap.get(key)
    │
    ├─ Index lookup: O(1)
    │   └─ sessionsByEmail.get(email)
    │
    └─ Compound query:
        └─ getActiveProfilesForStudent(email)
            │
            ├─ sessionsByEmail.get(email)          [O(1)]
            ├─ getRulesByScope("Student", id)      [O(1)]
            ├─ getRulesByScope("School", schoolId) [O(1)]
            ├─ getRulesByScope("Grade", grade)     [O(1)]
            └─ getRulesByScope("Class", classId)   [O(1)]
                │
                ▼
            Combine results
                │
                ▼
            Filter by isActiveNow()
                │
                ▼
            Return Set<Long> profileIds
```

**Performance**: All lookups are O(1), compound queries O(k) where k is small

---

## Component Responsibilities

### CacheManager
**Role**: Core cache logic and data management

**Responsibilities**:
- Maintain all hash maps
- Handle LISTEN/NOTIFY events
- Provide public API for cache access
- Execute scheduled cleanup

**Thread Safety**: All operations use ConcurrentHashMap

**Lifecycle**: ApplicationScoped singleton

---

### Main (REST Endpoints)
**Role**: HTTP API for cache access

**Endpoints**:
- `/health` - Database connectivity check
- `/cache/stats` - Cache statistics
- `/cache/sessions/{email}` - Sessions for student
- `/cache/profiles/active/{email}` - Active profiles for student
- `/cache/rules/school` - All school rules
- `/cache/cleanup` - Manual cleanup trigger

**Thread Model**: Non-blocking (Quarkus REST)

---

### PgSubscriber
**Role**: PostgreSQL LISTEN/NOTIFY client

**Connection**: Separate from query connection

**Channels**: 4 channels for 4 tables

**Handler**: Async callbacks to CacheManager

---

### Scheduled Tasks
**Role**: Periodic maintenance

**Tasks**:
- `cleanupStaleData()` - Every 6 hours

**Thread Pool**: Quarkus executor threads

---

## Performance Characteristics

### Memory Usage
- **Typical**: ~77 KB for 174 students, 138 sessions
- **Growth**: +30 KB per day (sessions)
- **Cleanup**: Frees ~30 KB every night

### Lookup Performance
- **Simple lookup**: O(1) - 1-2 microseconds
- **Index lookup**: O(1) - 2-5 microseconds
- **Compound query**: O(1) per scope - 10-20 microseconds total
- **List filtering**: O(k) where k is list size

### Update Performance
- **LISTEN/NOTIFY latency**: 50-100ms
- **Single update**: O(1) - 5-10 microseconds
- **Index update**: O(1) - 5-10 microseconds

### Cleanup Performance
- **Duration**: 10-50ms for 138 sessions
- **Frequency**: Every 6 hours
- **Impact**: Minimal (off hot path)

---

## Scalability Considerations

### Current Limits
- **Students**: Tested with 174, can handle 10,000+
- **Sessions**: 138 per day, ~50,000+ supported
- **Rules**: 10 active, ~1,000+ supported
- **Memory**: Linear growth with data size

### Bottlenecks
1. **Startup loading**: O(n) - scales with dataset size
2. **Index rebuilding**: O(n) - avoided by incremental updates
3. **Memory**: Bounded by session lifetime (1 day)

### Improvements Possible
- Add connection pooling for higher query throughput
- Implement pagination for large result sets
- Add caching layer (Caffeine) for hot data
- Partition data by school/grade for larger deployments

---

## Fault Tolerance

### Database Connection Loss
**Detection**: Query failures logged
**Recovery**: Automatic reconnection via Vert.x client
**Impact**: Queries fail until reconnection

### LISTEN/NOTIFY Connection Loss
**Detection**: Connection error callback
**Recovery**: PgSubscriber auto-reconnects
**Impact**: Missed notifications during downtime
**Mitigation**: Periodic cache reload (future enhancement)

### Partial Update Failure
**Scenario**: Rule update succeeds in DB, cache update fails
**Detection**: Exception logged
**Recovery**: Next scheduled cleanup corrects inconsistency
**Impact**: Temporary stale data (filtered by smart filtering)

---

## Monitoring Points

1. **Cache Stats**: `/cache/stats` - Monitor size growth
2. **Health Check**: `/health` - Database connectivity
3. **Cleanup Logs**: Search for "🧹" - Track cleanup effectiveness
4. **LISTEN/NOTIFY**: Search for "📢" - Verify real-time updates
5. **Startup Time**: Search for "✅ Cache loaded" - Track loading performance
