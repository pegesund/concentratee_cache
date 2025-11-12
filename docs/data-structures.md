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

## Memory Footprint

**Current Stats (Example)**:
```
studentsById:        174 entries  (~14 KB)
profilesById:        10 entries   (~2 KB)
rulesById:           1 entries    (~0.2 KB)
sessionsById:        138 entries  (~30 KB)
sessionsByEmail:     72 entries   (~30 KB)
sessionsByProfile:   1 entries    (~0.5 KB)
rulesByScopeAndValue: 1 entries   (~0.5 KB)

Total: ~77 KB
```

## Thread Safety

All hash maps use `ConcurrentHashMap` for thread-safe operations without explicit locking. Updates are atomic at the entry level.
