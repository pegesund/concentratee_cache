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

-- Function to notify on profile-related table changes (programs, categories, URLs)
CREATE OR REPLACE FUNCTION notify_profile_related_changes()
RETURNS trigger AS $$
DECLARE
    profile_id_val BIGINT;
    profile_category_id_val BIGINT;
BEGIN
    -- Extract profile_id from the changed row
    -- For tables with profile_id column (profiles_programs, profiles_categories)
    IF TG_TABLE_NAME IN ('profiles_programs', 'profiles_categories') THEN
        IF (TG_OP = 'DELETE') THEN
            profile_id_val := OLD.profile_id;
        ELSE
            profile_id_val := NEW.profile_id;
        END IF;
    -- For tables with profiles_category_id (profile_inactive_subcategories, profile_inactive_urls)
    ELSIF TG_TABLE_NAME IN ('profile_inactive_subcategories', 'profile_inactive_urls') THEN
        IF (TG_OP = 'DELETE') THEN
            profile_category_id_val := OLD.profiles_category_id;
            SELECT profile_id INTO profile_id_val FROM profiles_categories WHERE id = profile_category_id_val;
        ELSE
            profile_category_id_val := NEW.profiles_category_id;
            SELECT profile_id INTO profile_id_val FROM profiles_categories WHERE id = profile_category_id_val;
        END IF;
    END IF;

    -- Notify that a profile needs to be reloaded
    PERFORM pg_notify('profiles_changes', json_build_object(
        'operation', 'RELOAD',
        'id', profile_id_val,
        'trigger_table', TG_TABLE_NAME
    )::text);

    IF (TG_OP = 'DELETE') THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Function to notify on URL hierarchy changes (categories, subcategories, urls)
CREATE OR REPLACE FUNCTION notify_url_hierarchy_changes()
RETURNS trigger AS $$
BEGIN
    -- Notify that all profiles with this category/subcategory/url need to be reloaded
    PERFORM pg_notify('profiles_changes', json_build_object(
        'operation', 'RELOAD_ALL',
        'trigger_table', TG_TABLE_NAME,
        'changed_id', CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END
    )::text);

    IF (TG_OP = 'DELETE') THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Drop existing triggers if they exist
DROP TRIGGER IF EXISTS student_changes_trigger ON students;
DROP TRIGGER IF EXISTS profile_changes_trigger ON profiles;
DROP TRIGGER IF EXISTS rule_changes_trigger ON rules;
DROP TRIGGER IF EXISTS session_changes_trigger ON sessions;

-- Drop profile-related triggers
DROP TRIGGER IF EXISTS profiles_programs_changes_trigger ON profiles_programs;
DROP TRIGGER IF EXISTS profiles_categories_changes_trigger ON profiles_categories;
DROP TRIGGER IF EXISTS profile_inactive_subcategories_changes_trigger ON profile_inactive_subcategories;
DROP TRIGGER IF EXISTS profile_inactive_urls_changes_trigger ON profile_inactive_urls;
DROP TRIGGER IF EXISTS url_categories_changes_trigger ON url_categories;
DROP TRIGGER IF EXISTS url_subcategories_changes_trigger ON url_subcategories;
DROP TRIGGER IF EXISTS urls_changes_trigger ON urls;

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

-- Create profile-related triggers that notify when programs/categories change
CREATE TRIGGER profiles_programs_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON profiles_programs
    FOR EACH ROW EXECUTE FUNCTION notify_profile_related_changes();

CREATE TRIGGER profiles_categories_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON profiles_categories
    FOR EACH ROW EXECUTE FUNCTION notify_profile_related_changes();

CREATE TRIGGER profile_inactive_subcategories_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON profile_inactive_subcategories
    FOR EACH ROW EXECUTE FUNCTION notify_profile_related_changes();

CREATE TRIGGER profile_inactive_urls_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON profile_inactive_urls
    FOR EACH ROW EXECUTE FUNCTION notify_profile_related_changes();

-- Create URL hierarchy triggers
CREATE TRIGGER url_categories_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON url_categories
    FOR EACH ROW EXECUTE FUNCTION notify_url_hierarchy_changes();

CREATE TRIGGER url_subcategories_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON url_subcategories
    FOR EACH ROW EXECUTE FUNCTION notify_url_hierarchy_changes();

CREATE TRIGGER urls_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON urls
    FOR EACH ROW EXECUTE FUNCTION notify_url_hierarchy_changes();

-- Test the setup
SELECT 'Triggers installed successfully!' AS status;
