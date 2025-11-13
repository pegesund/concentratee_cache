-- =========================================
-- TEST: Future Sessions and Rules
-- =========================================
-- Tests that future sessions/rules are loaded and become active

\echo '========================================='
\echo 'FUTURE SESSIONS TEST'
\echo '========================================='
\echo ''

-- Clean up any existing test data
DELETE FROM sessions WHERE id >= 9500;
DELETE FROM rules WHERE id >= 9500;
DELETE FROM profiles WHERE id >= 9500;
DELETE FROM students WHERE id >= 9500;

-- Create test student
INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9500, 'future@test.com', 1, NOW(), NOW());

-- Create test profile
INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9500, 'Future Profile', '[]'::jsonb, 1, 1, true, NOW(), NOW());

-- TEST 1: Session starting tomorrow
\echo 'TEST 1: Creating session for tomorrow'
INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9500, 'Tomorrow Session',
        NOW() + INTERVAL '1 day',
        NOW() + INTERVAL '1 day 1 hour',
        9500, 1, 8, 9500, NOW(), NOW());

SELECT pg_sleep(0.5);

\echo 'Query: Session should be in database'
SELECT COUNT(*) as session_count FROM sessions WHERE id = 9500;

-- TEST 2: Rule starting tomorrow
\echo ''
\echo 'TEST 2: Creating rule for tomorrow'
INSERT INTO rules (id, scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at)
VALUES (9500, 'Student', '9500',
        NOW() + INTERVAL '1 day',
        NOW() + INTERVAL '2 days',
        9500, NOW(), NOW());

SELECT pg_sleep(0.5);

\echo 'Query: Rule should be in database'
SELECT COUNT(*) as rule_count FROM rules WHERE id = 9500;

-- TEST 3: Session in 3 days
\echo ''
\echo 'TEST 3: Creating session in 3 days'
INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9501, 'Day 3 Session',
        NOW() + INTERVAL '3 days',
        NOW() + INTERVAL '3 days 1 hour',
        9500, 1, 8, 9500, NOW(), NOW());

SELECT pg_sleep(0.5);

-- TEST 4: Session in 10 days (should NOT be loaded on restart)
\echo ''
\echo 'TEST 4: Creating session in 10 days (outside 7-day window)'
INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9502, 'Day 10 Session',
        NOW() + INTERVAL '10 days',
        NOW() + INTERVAL '10 days 1 hour',
        9500, 1, 8, 9500, NOW(), NOW());

SELECT pg_sleep(0.5);

\echo ''
\echo '========================================='
\echo 'SUMMARY'
\echo '========================================='
SELECT
    'Future test data created' as status,
    COUNT(CASE WHEN id >= 9500 THEN 1 END) as test_sessions
FROM sessions;

SELECT
    'Future test rules created' as status,
    COUNT(CASE WHEN id >= 9500 THEN 1 END) as test_rules
FROM rules;

\echo ''
\echo 'Now check cache API: GET /cache/profiles/active/future@test.com'
\echo 'Should return empty (future sessions not active yet)'
\echo ''
\echo 'After cache restart:'
\echo '- Sessions 9500, 9501 (within 7 days) should be loaded'
\echo '- Session 9502 (10 days) should NOT be loaded'
\echo ''
