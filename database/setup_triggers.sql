-- PostgreSQL triggers for LISTEN/NOTIFY on cache changes
-- This will notify the Java application when data changes

-- Function to notify on student changes
CREATE OR REPLACE FUNCTION notify_student_changes()
RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM pg_notify('students_changes', json_build_object(
            'operation', TG_OP,
            'id', OLD.id
        )::text);
        RETURN OLD;
    ELSE
        PERFORM pg_notify('students_changes', json_build_object(
            'operation', TG_OP,
            'id', NEW.id,
            'feide_email', NEW.feide_email
        )::text);
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Function to notify on profile changes
CREATE OR REPLACE FUNCTION notify_profile_changes()
RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM pg_notify('profiles_changes', json_build_object(
            'operation', TG_OP,
            'id', OLD.id
        )::text);
        RETURN OLD;
    ELSE
        PERFORM pg_notify('profiles_changes', json_build_object(
            'operation', TG_OP,
            'id', NEW.id,
            'name', NEW.name,
            'domains', NEW.domains,
            'teacher_id', NEW.teacher_id,
            'school_id', NEW.school_id,
            'is_whitelist_url', NEW.is_whitelist_url
        )::text);
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Function to notify on rule changes
CREATE OR REPLACE FUNCTION notify_rule_changes()
RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM pg_notify('rules_changes', json_build_object(
            'operation', TG_OP,
            'id', OLD.id
        )::text);
        RETURN OLD;
    ELSE
        PERFORM pg_notify('rules_changes', json_build_object(
            'operation', TG_OP,
            'id', NEW.id,
            'scope', NEW.scope,
            'scope_value', NEW.scope_value,
            'start_time', NEW.start_time,
            'end_time', NEW.end_time,
            'profile_id', NEW.profile_id
        )::text);
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Function to notify on session changes
CREATE OR REPLACE FUNCTION notify_session_changes()
RETURNS trigger AS $$
DECLARE
    student_email TEXT;
BEGIN
    -- Get student email for the session
    IF (TG_OP = 'DELETE') THEN
        SELECT feide_email INTO student_email FROM students WHERE id = OLD.student_id;
        PERFORM pg_notify('sessions_changes', json_build_object(
            'operation', TG_OP,
            'id', OLD.id,
            'student_id', OLD.student_id,
            'student_email', student_email
        )::text);
        RETURN OLD;
    ELSE
        SELECT feide_email INTO student_email FROM students WHERE id = NEW.student_id;
        PERFORM pg_notify('sessions_changes', json_build_object(
            'operation', TG_OP,
            'id', NEW.id,
            'title', NEW.title,
            'start_time', NEW.start_time,
            'end_time', NEW.end_time,
            'student_id', NEW.student_id,
            'student_email', student_email,
            'class_id', NEW.class_id,
            'teacher_id', NEW.teacher_id,
            'school_id', NEW.school_id,
            'teacher_session_id', NEW.teacher_session_id,
            'grade', NEW.grade,
            'profile_id', NEW.profile_id,
            'is_active', NEW.is_active,
            'percentage', NEW.percentage
        )::text);
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Drop existing triggers if they exist
DROP TRIGGER IF EXISTS student_changes_trigger ON students;
DROP TRIGGER IF EXISTS profile_changes_trigger ON profiles;
DROP TRIGGER IF EXISTS rule_changes_trigger ON rules;
DROP TRIGGER IF EXISTS session_changes_trigger ON sessions;

-- Create triggers
CREATE TRIGGER student_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON students
    FOR EACH ROW EXECUTE FUNCTION notify_student_changes();

CREATE TRIGGER profile_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON profiles
    FOR EACH ROW EXECUTE FUNCTION notify_profile_changes();

CREATE TRIGGER rule_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON rules
    FOR EACH ROW EXECUTE FUNCTION notify_rule_changes();

CREATE TRIGGER session_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON sessions
    FOR EACH ROW EXECUTE FUNCTION notify_session_changes();

-- Test the setup
SELECT 'Triggers installed successfully!' AS status;
