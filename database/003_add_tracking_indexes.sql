-- Add indexes for efficient tracking queries
-- This ensures we don't do full table scans when looking up sessions by teacher, student, etc.

-- Index on sessions.teacher_id for teacher tracking queries
CREATE INDEX IF NOT EXISTS idx_sessions_teacher_id ON sessions(teacher_id) WHERE teacher_id IS NOT NULL;

-- Index on sessions.student_id for student lookup
CREATE INDEX IF NOT EXISTS idx_sessions_student_id ON sessions(student_id) WHERE student_id IS NOT NULL;

-- Index on sessions.school_id for school-wide tracking
CREATE INDEX IF NOT EXISTS idx_sessions_school_id ON sessions(school_id) WHERE school_id IS NOT NULL;

-- Composite index on sessions for active session queries (start_time, end_time)
CREATE INDEX IF NOT EXISTS idx_sessions_active_time ON sessions(start_time, end_time) WHERE start_time IS NOT NULL AND end_time IS NOT NULL;

-- Index on rules.scope and scope_value for rule matching
CREATE INDEX IF NOT EXISTS idx_rules_scope_value ON rules(scope, scope_value) WHERE scope IS NOT NULL AND scope_value IS NOT NULL;

-- Index on rules for active rules (start_time, end_time)
CREATE INDEX IF NOT EXISTS idx_rules_active_time ON rules(start_time, end_time) WHERE start_time IS NOT NULL AND end_time IS NOT NULL;

-- Index on students.feide_email for fast student lookup
CREATE INDEX IF NOT EXISTS idx_students_email ON students(feide_email) WHERE feide_email IS NOT NULL;

-- Index on sessions.is_active for tracking status queries
CREATE INDEX IF NOT EXISTS idx_sessions_is_active ON sessions(is_active) WHERE is_active IS NOT NULL;

-- Composite index on sessions for teacher + active sessions
CREATE INDEX IF NOT EXISTS idx_sessions_teacher_active ON sessions(teacher_id, start_time, end_time) WHERE teacher_id IS NOT NULL;

-- Analyze tables to update statistics
ANALYZE sessions;
ANALYZE rules;
ANALYZE students;
