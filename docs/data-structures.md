# Data Structures

The cache system uses ConcurrentHashMap for thread-safe in-memory storage with derived indexes for O(1) lookups.

## Primary Hash Maps

### 1. studentsById
**Type**: `ConcurrentHashMap<Long, String>`

**Purpose**: Map student ID to email address

**Database Table**: `students`

**Loaded From**:
```sql
SELECT id, feide_email FROM students WHERE feide_email IS NOT NULL
```

**Key**: Student ID
**Value**: Student email (feide_email)

**Example**:
```java
studentsById.get(29L) → "30091073168@test.com"
```

---

### 2. profilesById
**Type**: `ConcurrentHashMap<Long, Profile>`

**Purpose**: Store all profile configurations

**Database Table**: `profiles`

**Loaded From**:
```sql
SELECT p.id, p.name, p.domains, p.teacher_id, p.school_id, p.is_whitelist_url
FROM profiles p
```

**Key**: Profile ID
**Value**: Profile object containing:
- `id`: Profile ID
- `name`: Profile name
- `domains`: JSON array of allowed/blocked domains
- `teacherId`: Owner teacher ID
- `schoolId`: School ID
- `isWhitelistUrl`: Boolean (whitelist vs blacklist)

**Example**:
```java
Profile p = profilesById.get(8L);
// p.name = "Updated Profile 90"
// p.domains = ["example.com", "test.com"]
```

---

### 3. rulesById
**Type**: `ConcurrentHashMap<Long, Rule>`

**Purpose**: Store all active rules for profile assignment

**Database Table**: `rules`

**Loaded From**:
```sql
SELECT id, scope, scope_value, start_time, end_time, profile_id
FROM rules
WHERE start_time <= CURRENT_DATE AND end_time >= CURRENT_DATE
```

**Key**: Rule ID
**Value**: Rule object containing:
- `id`: Rule ID
- `scope`: Rule type ("Student", "School", "Grade", "Class")
- `scopeValue`: Target identifier (e.g., "1" for school_id=1)
- `startTime`: When rule becomes active
- `endTime`: When rule expires
- `profileId`: Which profile to apply

**Example**:
```java
Rule r = rulesById.get(7L);
// r.scope = "School"
// r.scopeValue = "1"
// r.profileId = 8
```

---

### 4. sessionsById
**Type**: `ConcurrentHashMap<Long, Session>`

**Purpose**: Store all active sessions for today

**Database Table**: `sessions`

**Loaded From**:
```sql
SELECT id, title, start_time, end_time, student_id, class_id,
       teacher_id, school_id, teacher_session_id, grade, profile_id,
       is_active, percentage
FROM sessions
WHERE DATE(start_time) = CURRENT_DATE
```

**Key**: Session ID
**Value**: Session object containing:
- `id`: Session ID
- `title`: Session title
- `startTime`: Session start time
- `endTime`: Session end time
- `studentId`: Student ID
- `studentEmail`: Denormalized email (from studentsById)
- `classId`: Class ID
- `teacherId`: Teacher ID
- `schoolId`: School ID
- `grade`: Grade level
- `profileId`: Assigned profile (if any)
- `isActive`: Boolean flag
- `percentage`: Completion percentage

**Example**:
```java
Session s = sessionsById.get(221855L);
// s.studentId = 29
// s.studentEmail = "30091073168@test.com"
// s.profileId = null
// s.schoolId = 1
```

---

## Derived Indexes

### 5. sessionsByEmail
**Type**: `ConcurrentHashMap<String, List<Session>>`

**Purpose**: Fast lookup of sessions by student email

**Built From**: `sessionsById` + `studentsById`

**Build Process**:
```java
for (Session session : sessionsById.values()) {
    if (session.studentEmail != null) {
        sessionsByEmail
            .computeIfAbsent(session.studentEmail, k -> new ArrayList<>())
            .add(session);
    }
}
```

**Key**: Student email
**Value**: List of all sessions for that student

**Filtering**: Returns only **today's sessions** when accessed

**Performance**: O(1) lookup, O(k) where k = sessions per student

**Example**:
```java
List<Session> sessions = getSessionsByEmail("30091073168@test.com");
// Returns only sessions where startTime.toLocalDate() == today
```

---

### 6. sessionsByProfile
**Type**: `ConcurrentHashMap<Long, List<Session>>`

**Purpose**: Fast lookup of sessions using a specific profile

**Built From**: `sessionsById`

**Build Process**:
```java
for (Session session : sessionsById.values()) {
    if (session.profileId != null) {
        sessionsByProfile
            .computeIfAbsent(session.profileId, k -> new ArrayList<>())
            .add(session);
    }
}
```

**Key**: Profile ID
**Value**: List of all sessions using that profile

**Performance**: O(1) lookup

**Example**:
```java
List<Session> sessions = sessionsByProfile.get(8L);
// Returns all sessions with profileId = 8
```

---

### 7. rulesByScopeAndValue
**Type**: `ConcurrentHashMap<String, ConcurrentHashMap<String, List<Rule>>>`

**Purpose**: Fast lookup of rules by scope and value (compound index)

**Built From**: `rulesById`

**Build Process**:
```java
for (Rule rule : rulesById.values()) {
    if (rule.scope != null && rule.scopeValue != null) {
        rulesByScopeAndValue
            .computeIfAbsent(rule.scope, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(rule.scopeValue, k -> new ArrayList<>())
            .add(rule);
    }
}
```

**Key Level 1**: Scope type ("Student", "School", "Grade", "Class")
**Key Level 2**: Scope value (e.g., "1" for school_id=1)
**Value**: List of rules matching that scope+value

**Performance**: O(1) lookup

**Example**:
```java
// Get all school-wide rules for school 1
List<Rule> rules = getRulesByScope("School", "1");
// Returns rules where scope="School" AND scopeValue="1"
```

**Index Structure**:
```
rulesByScopeAndValue = {
    "School": {
        "1": [Rule(id=7, profileId=8, ...)]
    },
    "Student": {
        "123": [Rule(id=10, profileId=5, ...)]
    },
    "Grade": {
        "6": [Rule(id=12, profileId=3, ...)]
    }
}
```

---

## Tracking System Data Structures

### 8. sessionTrackers (TrackingManager)
**Type**: `ConcurrentHashMap<Long, SessionTracker>`

**Purpose**: Track real-time student activity in active sessions

**Location**: `TrackingManager.java:38`

**Key**: Session ID
**Value**: SessionTracker object containing:
- Session metadata (start/end times, duration)
- Map of StudentMinuteTrackers (per student in session)
- Real-time attendance statistics

**Example**:
```java
SessionTracker tracker = sessionTrackers.get(123456L);
Map<String, AttendanceStats> stats = tracker.calculateStats();
// Returns real-time stats for all students in session
```

**Lifecycle**:
- Created when first heartbeat received for session
- Updated every minute during rotation
- Persisted to database when session ends
- Removed from memory during cleanup (every 5 minutes)

---

### 9. ruleTrackers (TrackingManager)
**Type**: `ConcurrentHashMap<String, RuleTracker>`

**Purpose**: Track student activity for rule-based contexts (without sessions)

**Location**: `TrackingManager.java:39`

**Key**: Rule context key (format: `"scope:scopeValue"`, e.g., `"school:1"`)
**Value**: RuleTracker object containing:
- Rule metadata (scope, scopeValue, profileId)
- Map of StudentMinuteTrackers (per student matching rule)
- School ID for indexing

**Example**:
```java
RuleTracker tracker = ruleTrackers.get("school:1");
Map<String, AttendanceStats> stats = tracker.calculateStats();
// Returns stats for all students in school 1 (without active session)
```

**Context Key Examples**:
- `"school:1"` - All students in school 1
- `"grade:8"` - All 8th grade students
- `"class:42"` - All students in class 42

**Lifecycle**:
- Created when first heartbeat received matching rule (and no session)
- Updated every minute during rotation
- Removed if no activity for 30 minutes (stale cleanup)

---

### 10. studentEmailToSessionIds (TrackingManager)
**Type**: `ConcurrentHashMap<String, Set<Long>>`

**Purpose**: Index sessions by student email for fast lookup

**Location**: `TrackingManager.java:42`

**Key**: Student email
**Value**: Set of session IDs being tracked for that student

**Example**:
```java
Set<Long> sessionIds = studentEmailToSessionIds.get("student@test.com");
// Returns {123456, 123457} if student is tracked in 2 sessions
```

**Use Case**: Avoid full table scan when looking up sessions for a student

---

### 11. teacherIdToSessionIds (TrackingManager)
**Type**: `ConcurrentHashMap<Long, Set<Long>>`

**Purpose**: Index sessions by teacher ID for fast lookup

**Location**: `TrackingManager.java:43`

**Key**: Teacher ID
**Value**: Set of session IDs owned by that teacher

**Example**:
```java
Set<Long> sessionIds = teacherIdToSessionIds.get(42L);
// Returns all session IDs for teacher 42
```

**Use Case**: Teacher dashboard showing all their active sessions

---

### 12. schoolIdToRuleContextKeys (TrackingManager)
**Type**: `ConcurrentHashMap<Long, Set<String>>`

**Purpose**: Index rule contexts by school ID for fast lookup

**Location**: `TrackingManager.java:44`

**Key**: School ID
**Value**: Set of rule context keys for that school

**Example**:
```java
Set<String> contextKeys = schoolIdToRuleContextKeys.get(1L);
// Returns {"school:1", "grade:8", "class:42"} for school 1
```

**Use Case**: School-wide tracking queries and cleanup

---

## Memory Footprint

**Cache System (Example)**:
```
studentsById:        174 entries  (~14 KB)
profilesById:        10 entries   (~2 KB)
rulesById:           1 entries    (~0.2 KB)
sessionsById:        138 entries  (~30 KB)
sessionsByEmail:     72 entries   (~30 KB)
sessionsByProfile:   1 entries    (~0.5 KB)
rulesByScopeAndValue: 1 entries   (~0.5 KB)

Subtotal: ~77 KB
```

**Tracking System (Example)**:
```
sessionTrackers:           5 sessions × 1.6 KB   (~8 KB)
ruleTrackers:              2 contexts × 0.5 KB   (~1 KB)
studentEmailToSessionIds:  12 students           (~0.5 KB)
teacherIdToSessionIds:     3 teachers            (~0.2 KB)
schoolIdToRuleContextKeys: 1 school              (~0.1 KB)

Subtotal: ~10 KB
```

**Total Memory Usage: ~87 KB**

**Scaling Example**:
```
100 active sessions × 30 students each:
- sessionTrackers: 100 × 1.6 KB = 160 KB
- Indexes: ~5 KB
Total: ~165 KB

Very memory efficient due to:
- Binary counters (0 or 1) in history
- Fixed 4-minute rolling window
- Automatic cleanup of ended sessions
```

## Thread Safety

All hash maps use `ConcurrentHashMap` for thread-safe operations without explicit locking. Updates are atomic at the entry level.

**Tracking System Thread Safety**:
- `sessionTrackers` / `ruleTrackers`: `ConcurrentHashMap`
- `StudentMinuteTracker.currentMinuteCounter`: `AtomicInteger`
- `StudentMinuteTracker.history`: `ConcurrentLinkedDeque`
- All index sets: `ConcurrentHashMap.newKeySet()`

Multiple threads can record heartbeats concurrently without data corruption or lost updates.
