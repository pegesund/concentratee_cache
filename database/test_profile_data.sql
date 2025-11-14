-- Test data for profile API parity tests
-- This creates a complete test setup with programs, categories, subcategories, and URLs
-- Uses simple approach: update existing data or use what exists

-- Use an existing student (14080899573@test.com = student id 42) for our tests
-- Change their email to test@example.com for easier testing
UPDATE students SET feide_email = 'test@example.com' WHERE id = 42;

-- Update an existing profile (id=1) to have test data
UPDATE profiles SET
    name = 'Test Profile',
    domains = '[{"id": "11111111-1111-1111-1111-111111111111", "name": "test.com"}, {"id": "22222222-2222-2222-2222-222222222222", "name": "example.com"}]'::jsonb,
    teacher_id = 1,
    school_id = 1,
    is_whitelist_url = true
WHERE id = 1;

-- Clear existing program links for profile 1
DELETE FROM profiles_programs WHERE profile_id = 1;

-- Link programs to profile (assumes programs already exist)
INSERT INTO profiles_programs (profile_id, program_id)
SELECT 1, id FROM programs LIMIT 2
ON CONFLICT DO NOTHING;

-- Clear existing categories for profile 1
DELETE FROM profiles_categories WHERE profile_id = 1;

-- Link categories to profile (assumes url_categories already exist)
INSERT INTO profiles_categories (profile_id, url_category_id, is_active)
SELECT 1, id, true FROM url_categories LIMIT 2
ON CONFLICT (profile_id, url_category_id) DO UPDATE SET is_active = true;

-- Create an active session for this student with this profile (today)
INSERT INTO sessions (id, title, start_time, end_time, student_id, class_id, teacher_id, school_id, teacher_session_id, grade, profile_id, is_active, percentage, inserted_at, updated_at)
VALUES (9999, 'Test Session',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP + INTERVAL '2 hours',
        42, 1, 1, 1, 1, 10, 1, true, 85.5,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET
    title = 'Test Session',
    start_time = CURRENT_TIMESTAMP,
    end_time = CURRENT_TIMESTAMP + INTERVAL '2 hours',
    student_id = 42,
    profile_id = 1,
    is_active = true,
    percentage = 85.5,
    updated_at = CURRENT_TIMESTAMP;

SELECT 'Test profile data created successfully!' AS status;
