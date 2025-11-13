# API Endpoints

REST API documentation for the Concentratee cache service.

## Base URL

```
http://localhost:8082
```

---

## Health & Monitoring

### GET /health

Check database connectivity and get current timestamp.

**Response**: JSON
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/health
```

**Success Response**:
```json
{
  "status": "ok",
  "database": "concentratee_dev",
  "timestamp": "2025-11-12 22:47:20.237"
}
```

**Error Response**:
```json
{
  "status": "error",
  "message": "Connection refused"
}
```

---

### GET /cache/stats

Get cache statistics including size of all hash maps and indexes.

**Response**: JSON
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/cache/stats
```

**Response**:
```json
{
  "studentsById": 174,
  "profilesById": 10,
  "rulesById": 1,
  "sessionsById": 138,
  "sessionsByEmail": 72,
  "sessionsByProfile": 1,
  "rulesByScopeAndValue": 1
}
```

**Fields**:
- `studentsById`: Number of students with emails
- `profilesById`: Total profiles in cache
- `rulesById`: Active rules in cache
- `sessionsById`: Total sessions (today's sessions)
- `sessionsByEmail`: Number of unique student emails with sessions
- `sessionsByProfile`: Number of profiles currently in use
- `rulesByScopeAndValue`: Number of scope types indexed

---

## Cache Queries

### GET /cache/sessions/{email}

Get all sessions for a student by email.

**Parameters**:
- `email` (path): Student email address

**Response**: Plain text
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/cache/sessions/30091073168@test.com
```

**Response**:
```
Sessions for 30091073168@test.com:
  - Session{id=221855, studentId=29, profileId=null, grade=6, startTime=2025-11-12T09:00, endTime=2025-11-12T09:45}
  - Session{id=5847994, studentId=29, profileId=null, grade=6, startTime=2025-11-12T15:35, endTime=2025-11-12T15:40}
```

**No Sessions**:
```
No sessions found for: test@example.com
```

**Note**: Only returns sessions for TODAY (filtered automatically)

---

### GET /cache/profiles/active/{email}

Get all active profiles for a student, including both session-based and rule-based profiles.

**Parameters**:
- `email` (path): Student email address
- `track` (query, optional): Set to `true` to record heartbeat for appearance tracking
- `expand` (query, optional): Set to `false` to suppress profile details (default: true)

**Response**: Plain text
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/cache/profiles/active/30091073168@test.com
```

**With tracking enabled**:
```bash
curl "http://localhost:8082/cache/profiles/active/30091073168@test.com?track=true"
```

**Response**:
```
Active profiles for 30091073168@test.com:
  - Profile 8: Updated Profile 90
```

**No Profiles**:
```
No active profiles for: test@example.com
```

**Logic**:
1. Looks up student's sessions
2. Checks session.profileId for session-based profiles
3. Checks rules matching:
   - Direct student: `scope=Student, scopeValue={studentId}`
   - School-wide: `scope=School, scopeValue={schoolId}`
   - Grade-level: `scope=Grade, scopeValue={grade}`
   - Class-specific: `scope=Class, scopeValue={classId}`
4. Filters by `isActiveNow()` for both sessions and rules
5. Returns unique set of profile IDs
6. If `track=true`, records heartbeat in tracking system

**Performance**: O(1) for each rule scope lookup

---

### GET /cache/rules/school

Get all school-scoped rules.

**Response**: Plain text
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/cache/rules/school
```

**Response**:
```
School rules: 1
  - Rule{id=7, scope=School, scopeValue=1, profileId=8}
```

---

## Maintenance

### GET /cache/cleanup

Manually trigger stale data cleanup.

**Response**: Plain text
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/cache/cleanup
```

**Response**:
```
Cleanup triggered successfully
```

**Effect**:
- Removes sessions with `startTime < today`
- Removes rules with `endTime < now`
- Updates all indexes

**Log Output**:
```
🧹 Starting scheduled cleanup of stale data...
✅ Cleanup completed: removed 0 old sessions and 0 expired rules
```

**Use Cases**:
- Testing cleanup logic
- Manual memory management after bulk deletions
- Emergency cleanup if scheduled task fails

---

## Error Responses

### 404 Not Found

**Scenario**: Invalid endpoint

**Response**:
```
Not Found
```

---

### 500 Internal Server Error

**Scenario**: Database connection lost or query failure

**Response** (JSON if from /health):
```json
{
  "status": "error",
  "message": "Pool closed"
}
```

**Response** (Plain text from other endpoints):
```
Internal Server Error
```

---

## Response Formats

### JSON Endpoints
- `/health`
- `/cache/stats`

**Content-Type**: `application/json`

---

### Plain Text Endpoints
- `/cache/sessions/{email}`
- `/cache/profiles/active/{email}`
- `/cache/rules/school`
- `/cache/cleanup`

**Content-Type**: `text/plain; charset=utf-8`

---

## Performance Characteristics

| Endpoint | Complexity | Typical Response Time |
|----------|-----------|----------------------|
| `/health` | O(1) | 5-10ms |
| `/cache/stats` | O(1) | <1ms |
| `/cache/sessions/{email}` | O(k) | 1-5ms |
| `/cache/profiles/active/{email}` | O(1) | 2-10ms |
| `/cache/rules/school` | O(n) | 1-5ms |
| `/cache/cleanup` | O(n) | 10-50ms |

Where:
- k = number of sessions for student (typically 1-5)
- n = number of rules/sessions (typically 1-138)

---

## Example Usage Scenarios

### Check if student has active profile

```bash
curl http://localhost:8082/cache/profiles/active/student@test.com
```

If output contains profile names → student is restricted
If output says "No active profiles" → student has no restrictions

---

### Monitor cache health

```bash
# Check database connectivity
curl http://localhost:8082/health

# Check cache sizes
curl http://localhost:8082/cache/stats
```

---

### Debug session assignment

```bash
# Get all sessions for student
curl http://localhost:8082/cache/sessions/student@test.com

# Check active profiles
curl http://localhost:8082/cache/profiles/active/student@test.com

# Compare session.profileId with active profiles
```

---

### Test cleanup behavior

```bash
# Before cleanup
curl http://localhost:8082/cache/stats

# Trigger cleanup
curl http://localhost:8082/cache/cleanup

# After cleanup
curl http://localhost:8082/cache/stats

# Sessions and rules counts should decrease if stale data existed
```

---

## Tracking Endpoints

### GET /tracking/stats

Get tracking system statistics.

**Response**: JSON
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/tracking/stats
```

**Response**:
```json
{
  "activeSessions": 5,
  "activeRuleContexts": 2,
  "indexedStudents": 12,
  "indexedTeachers": 3,
  "indexedSchools": 1
}
```

**Fields**:
- `activeSessions`: Number of sessions currently being tracked
- `activeRuleContexts`: Number of rule contexts being tracked
- `indexedStudents`: Number of unique students indexed
- `indexedTeachers`: Number of unique teachers indexed
- `indexedSchools`: Number of unique schools indexed

---

### GET /tracking/session/{sessionId}

Get real-time tracking data for a specific session.

**Parameters**:
- `sessionId` (path): Session ID

**Response**: JSON
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/tracking/session/123456
```

**Response**:
```json
{
  "sessionId": 123456,
  "students": [
    {
      "email": "student@test.com",
      "currentlyActive": true,
      "last3Minutes": [1, 1, 0],
      "totalActiveMinutes": 25,
      "totalMinutes": 30,
      "percentage": 83.33,
      "isActive": true
    }
  ]
}
```

**Empty Response** (no tracking data):
```json
{
  "sessionId": 123456,
  "students": []
}
```

**Fields**:
- `currentlyActive`: Was student active in most recent rotated minute
- `last3Minutes`: Binary history [minute-1, minute-2, minute-3] (1=active, 0=inactive)
- `totalActiveMinutes`: Total minutes with activity recorded
- `totalMinutes`: Session duration in minutes
- `percentage`: Attendance percentage (totalActiveMinutes / totalMinutes)
- `isActive`: True if percentage > 80%

---

### GET /tracking/teacher/{teacherId}

Get tracking data for all sessions owned by a teacher.

**Parameters**:
- `teacherId` (path): Teacher ID

**Response**: JSON
**Status**: 200 OK

**Example**:
```bash
curl http://localhost:8082/tracking/teacher/42
```

**Response**:
```json
{
  "teacherId": 42,
  "sessions": [
    {
      "sessionId": 123456,
      "students": [
        {
          "email": "student1@test.com",
          "percentage": 83.33,
          "isActive": true
        },
        {
          "email": "student2@test.com",
          "percentage": 45.67,
          "isActive": false
        }
      ]
    },
    {
      "sessionId": 123457,
      "students": [...]
    }
  ]
}
```

**No Sessions**:
```json
{
  "teacherId": 42,
  "sessions": []
}
```

---

## Future Endpoints (Planned)

### GET /cache/profile/{id}
Get profile details by ID

### GET /cache/rule/{id}
Get rule details by ID

### GET /cache/sessions/active
Get all currently active sessions

### POST /cache/reload
Force full cache reload from database

### GET /metrics
Prometheus metrics endpoint
