# Database Integration

The cache integrates with PostgreSQL using reactive queries and real-time synchronization via LISTEN/NOTIFY.

## Database Tables

### students
**Columns Used**:
- `id` (bigint): Primary key
- `feide_email` (varchar): Student email address
- `school_id` (bigint): School FK (used for tracking)

**Cache Mapping**:
- → `studentsById` (id → email mapping, simple)
- → `CacheManager.studentsByEmail` (email → Student object, for tracking)

**Query**:
```sql
SELECT id, feide_email, school_id
FROM students
WHERE feide_email IS NOT NULL
```

**Update Detection**: LISTEN/NOTIFY on `students_changes` channel

**Note**: The `Student` model object (with id, email, schoolId) is used by the tracking system to determine which rules apply to a student.

**Index for Tracking** (see `database/003_add_tracking_indexes.sql`):
- `idx_students_email` - Fast student lookup by email

---

### profiles
**Columns Used**:
- `id` (bigint): Primary key
- `name` (varchar): Profile name
- `domains` (jsonb): Array of domains
- `teacher_id` (bigint): Owner teacher
- `school_id` (bigint): School
- `is_whitelist_url` (boolean): Whitelist vs blacklist

**Cache Mapping**: → `profilesById`

**Query**:
```sql
SELECT p.id, p.name, p.domains, p.teacher_id, p.school_id, p.is_whitelist_url
FROM profiles p
```

**Update Detection**: LISTEN/NOTIFY on `profiles_changes` channel

---

### rules
**Columns Used**:
- `id` (bigint): Primary key
- `scope` (varchar): "Student", "School", "Grade", "Class"
- `scope_value` (varchar): Target identifier
- `start_time` (timestamp): Rule start date/time
- `end_time` (timestamp): Rule end date/time
- `profile_id` (bigint): Profile to apply

**Cache Mapping**: → `rulesById`, `rulesByScopeAndValue`

**Query** (loads only active rules):
```sql
SELECT id, scope, scope_value, start_time, end_time, profile_id
FROM rules
WHERE start_time <= CURRENT_DATE AND end_time >= CURRENT_DATE
```

**Update Detection**: LISTEN/NOTIFY on `rules_changes` channel

---

### sessions
**Columns Used**:
- `id` (bigint): Primary key
- `title` (varchar): Session title
- `start_time` (timestamp): Session start
- `end_time` (timestamp): Session end
- `student_id` (bigint): Student FK
- `class_id` (bigint): Class FK
- `teacher_id` (bigint): Teacher FK
- `school_id` (bigint): School FK
- `teacher_session_id` (bigint): Teacher session FK
- `grade` (integer): Grade level
- `profile_id` (bigint): Assigned profile
- `is_active` (boolean): **Tracking aggregate** - Session-level active flag (80% of students must be active)
- `percentage` (double): **Tracking aggregate** - Average attendance percentage across all students

**Cache Mapping**: → `sessionsById`, `sessionsByEmail`, `sessionsByProfile`

**Tracking Mapping**: → `TrackingManager.sessionTrackers` (in-memory until session ends)

**Query** (loads only today's sessions):
```sql
SELECT id, title, start_time, end_time, student_id, class_id,
       teacher_id, school_id, teacher_session_id, grade, profile_id,
       is_active, percentage
FROM sessions
WHERE DATE(start_time) = CURRENT_DATE
```

**Update Detection**: LISTEN/NOTIFY on `sessions_changes` channel

**Tracking Persistence**:
- `is_active` and `percentage` are written by `TrackingManager.persistSessionTracking()`
- Persisted when session ends (during cleanup, every 5 minutes)
- Values are aggregate calculations, not per-student:
  ```java
  // Average percentage across all students
  percentage = avg(student1.percentage, student2.percentage, ...)

  // True if 80% of students are individually active
  is_active = (activeStudents / totalStudents) > 0.8
  ```

**Indexes for Tracking Queries** (see `database/003_add_tracking_indexes.sql`):
- `idx_sessions_teacher_id` - Teacher dashboard queries
- `idx_sessions_student_id` - Student lookup
- `idx_sessions_active_time` - Active session filtering
- `idx_sessions_is_active` - Status queries
- `idx_sessions_teacher_active` - Teacher + active session composite

---

## PostgreSQL LISTEN/NOTIFY

### Setup

Database triggers send notifications on INSERT/UPDATE/DELETE:

```sql
-- Example: Session changes trigger
CREATE TRIGGER session_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON sessions
    FOR EACH ROW EXECUTE FUNCTION notify_session_changes();
```

### Notification Payload

JSON format with operation type and data:

**INSERT/UPDATE**:
```json
{
  "operation": "UPDATE",
  "id": 7,
  "scope": "School",
  "scope_value": "1",
  "start_time": "2025-11-11T00:00:00",
  "end_time": "2025-11-19T00:00:00",
  "profile_id": 8
}
```

**DELETE**:
```json
{
  "operation": "DELETE",
  "id": 7
}
```

### Channels

| Channel | Trigger | Handler |
|---------|---------|---------|
| `students_changes` | students table | `handleStudentChange()` |
| `profiles_changes` | profiles table | `handleProfileChange()` |
| `rules_changes` | rules table | `handleRuleChange()` |
| `sessions_changes` | sessions table | `handleSessionChange()` |

### Change Handlers

#### handleStudentChange()
**On DELETE**:
1. Remove from `studentsById`
2. Remove from `sessionsByEmail`
3. Set `studentEmail = null` for affected sessions

**On INSERT/UPDATE**:
1. Reload student from database
2. Update `studentsById`
3. Update `studentEmail` for all sessions with that student_id
4. Rebuild `sessionsByEmail` for affected emails

#### handleProfileChange()
**On DELETE**:
1. Remove from `profilesById`

**On INSERT/UPDATE**:
1. Reload profile from database
2. Update `profilesById`

#### handleRuleChange()
**On DELETE**:
1. Remove from `rulesById`
2. Remove from `rulesByScopeAndValue` index

**On INSERT/UPDATE**:
1. Remove old rule from `rulesByScopeAndValue` index
2. Reload rule from database
3. Update `rulesById`
4. Add to `rulesByScopeAndValue` index

#### handleSessionChange()
**On DELETE**:
1. Remove from `sessionsById`
2. Remove from `sessionsByEmail` index
3. Remove from `sessionsByProfile` index

**On INSERT/UPDATE**:
1. Remove old session from indexes
2. Reload session from database
3. Update `sessionsById`
4. Lookup `studentEmail` from `studentsById`
5. Add to `sessionsByEmail` index
6. Add to `sessionsByProfile` index

---

## Startup Loading Sequence

```
1. Load students (required first for email denormalization)
   ↓
2. Load profiles, rules, sessions (in parallel)
   ↓
3. Build derived indexes:
   - sessionsByEmail
   - sessionsByProfile
   - rulesByScopeAndValue
   ↓
4. Setup LISTEN/NOTIFY listeners
   ↓
5. Cache ready ✅
```

**Code Reference**: `CacheManager.onStart()` (lines 48-83)

---

## Connection Configuration

**Database**: `concentratee_dev`
**Host**: `localhost:5432`
**User**: `postgres`

**Reactive Client**: Vert.x PostgreSQL Reactive Client
**LISTEN/NOTIFY Client**: PgSubscriber (separate connection)
