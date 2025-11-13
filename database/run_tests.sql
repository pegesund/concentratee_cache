-- =====================================================
-- COMPREHENSIVE CACHE TEST SUITE
-- =====================================================
-- Tests 10+ scenarios covering sessions, rules, profiles
-- and time-based filtering with LISTEN/NOTIFY validation
-- =====================================================

\set QUIET on
\set ON_ERROR_STOP on

\echo '========================================='
\echo 'CACHE TEST SUITE - Starting'
\echo '========================================='
\echo ''

-- Store original counts for cleanup verification
CREATE TEMP TABLE test_baseline AS
SELECT 
    (SELECT COUNT(*) FROM students WHERE id >= 9000) as students_count,
    (SELECT COUNT(*) FROM profiles WHERE id >= 9000) as profiles_count,
    (SELECT COUNT(*) FROM rules WHERE id >= 9000) as rules_count,
    (SELECT COUNT(*) FROM sessions WHERE id >= 9000) as sessions_count;

\echo 'Baseline counts stored'
\echo ''

-- =====================================================
-- TEST 1: Active session with profile assignment
-- =====================================================
\echo 'TEST 1: Active session with profile assignment'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9001, 'test1@cache.test', 1, NOW(), NOW());

INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9001, 'Test Profile 1', '[]'::jsonb, 1, 1, true, NOW(), NOW());

INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9001, 'Active Session',
        NOW() - INTERVAL '5 minutes',
        NOW() + INTERVAL '1 hour',
        9001, 1, 6, 9001, NOW(), NOW());

\echo 'Expected: 1 active session, profile 9001 active'
SELECT 'DB Check:' as test, COUNT(*) as active_sessions 
FROM sessions 
WHERE id = 9001 
  AND start_time <= NOW() 
  AND end_time >= NOW();

\echo 'Waiting for LISTEN/NOTIFY propagation...'
SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 2: Expired session (should not be active)
-- =====================================================
\echo 'TEST 2: Expired session (should not be active)'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9002, 'test2@cache.test', 1, NOW(), NOW());

INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9002, 'Expired Session',
        NOW() - INTERVAL '2 hours',
        NOW() - INTERVAL '1 hour',
        9002, 1, 7, 9001, NOW(), NOW());

\echo 'Expected: 0 active sessions (expired)'
SELECT 'DB Check:' as test, COUNT(*) as active_sessions 
FROM sessions 
WHERE id = 9002 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 3: Future session (should not be active yet)
-- =====================================================
\echo 'TEST 3: Future session (should not be active yet)'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9003, 'test3@cache.test', 1, NOW(), NOW());

INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9003, 'Future Session', 
        NOW() + INTERVAL '1 hour', 
        NOW() + INTERVAL '2 hours',
        9003, 1, 8, 9001, NOW(), NOW());

\echo 'Expected: 0 active sessions (future)'
SELECT 'DB Check:' as test, COUNT(*) as active_sessions 
FROM sessions 
WHERE id = 9003 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 4: School-wide rule (active)
-- =====================================================
\echo 'TEST 4: School-wide rule (currently active)'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9004, 'test4@cache.test', 1, NOW(), NOW());

INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9002, 'School Rule Profile', '[]'::jsonb, 1, 1, true, NOW(), NOW());

INSERT INTO rules (id, scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at)
VALUES (9001, 'School', '1',
        NOW() - INTERVAL '1 day',
        NOW() + INTERVAL '1 day',
        9002, NOW(), NOW());

\echo 'Expected: 1 active rule for school 1'
SELECT 'DB Check:' as test, COUNT(*) as active_rules 
FROM rules 
WHERE id = 9001 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 5: Grade-level rule
-- =====================================================
\echo 'TEST 5: Grade-level rule'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9005, 'test5@cache.test', 1, NOW(), NOW());

INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9003, 'Grade 9 Profile', '[]'::jsonb, 1, 1, false, NOW(), NOW());

-- Active session for grade 9
INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9004, 'Grade 9 Session',
        NOW() - INTERVAL '5 minutes',
        NOW() + INTERVAL '30 minutes',
        9005, 1, 9, NULL, NOW(), NOW());

-- Grade-level rule
INSERT INTO rules (id, scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at)
VALUES (9002, 'Grade', '9',
        NOW() - INTERVAL '1 hour',
        NOW() + INTERVAL '1 hour',
        9003, NOW(), NOW());

\echo 'Expected: 1 active rule for grade 9'
SELECT 'DB Check:' as test, COUNT(*) as active_rules 
FROM rules 
WHERE id = 9002 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 6: Student-specific rule
-- =====================================================
\echo 'TEST 6: Student-specific rule'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9006, 'test6@cache.test', 1, NOW(), NOW());

INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9004, 'Student Specific Profile', '["example.com"]'::jsonb, 1, 1, true, NOW(), NOW());

-- Session without profile
INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9005, 'Student Session',
        NOW() - INTERVAL '5 minutes',
        NOW() + INTERVAL '45 minutes',
        9006, 1, 5, NULL, NOW(), NOW());

-- Student-specific rule
INSERT INTO rules (id, scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at)
VALUES (9003, 'Student', '9006',
        NOW() - INTERVAL '5 minutes',
        NOW() + INTERVAL '2 hours',
        9004, NOW(), NOW());

\echo 'Expected: 1 active rule for student 9006'
SELECT 'DB Check:' as test, COUNT(*) as active_rules 
FROM rules 
WHERE id = 9003 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 7: Multiple active sessions for one student
-- =====================================================
\echo 'TEST 7: Multiple active sessions for one student'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9007, 'test7@cache.test', 1, NOW(), NOW());

INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES
    (9006, 'Session 1', NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 9007, 1, 6, 9001, NOW(), NOW()),
    (9007, 'Session 2', NOW() - INTERVAL '5 minutes', NOW() + INTERVAL '1 hour', 9007, 1, 6, 9001, NOW(), NOW());

\echo 'Expected: 2 active sessions for student 9007'
SELECT 'DB Check:' as test, COUNT(*) as active_sessions 
FROM sessions 
WHERE student_id = 9007 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 8: Expired rule (should not be active)
-- =====================================================
\echo 'TEST 8: Expired rule (should not be active)'
\echo '-----------------------------------------------'

INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9005, 'Expired Rule Profile', '[]'::jsonb, 1, 1, true, NOW(), NOW());

INSERT INTO rules (id, scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at)
VALUES (9004, 'School', '1',
        NOW() - INTERVAL '2 days',
        NOW() - INTERVAL '1 day',
        9005, NOW(), NOW());

\echo 'Expected: 0 active rules (expired)'
SELECT 'DB Check:' as test, COUNT(*) as active_rules 
FROM rules 
WHERE id = 9004 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 9: Rule at exact boundary times
-- =====================================================
\echo 'TEST 9: Rule at exact boundary times (inclusive)'
\echo '-----------------------------------------------'

INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9006, 'Boundary Test Profile', '[]'::jsonb, 1, 1, true, NOW(), NOW());

-- Rule that starts slightly in the past
INSERT INTO rules (id, scope, scope_value, start_time, end_time, profile_id, inserted_at, updated_at)
VALUES (9005, 'School', '1',
        NOW() - INTERVAL '1 minute',
        NOW() + INTERVAL '1 hour',
        9006, NOW(), NOW());

\echo 'Expected: 1 active rule (at start boundary)'
SELECT 'DB Check:' as test, COUNT(*) as active_rules 
FROM rules 
WHERE id = 9005 
  AND start_time <= NOW() 
  AND end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 10: Session UPDATE (change profile)
-- =====================================================
\echo 'TEST 10: Session UPDATE (change profile via LISTEN/NOTIFY)'
\echo '-----------------------------------------------'

\echo 'Initial state: Session 9001 has profile 9001'
SELECT 'DB Check:' as test, profile_id 
FROM sessions 
WHERE id = 9001;

UPDATE sessions 
SET profile_id = 9002, updated_at = NOW()
WHERE id = 9001;

\echo 'Updated state: Session 9001 now has profile 9002'
SELECT 'DB Check:' as test, profile_id 
FROM sessions 
WHERE id = 9001;

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 11: Rule DELETE (via LISTEN/NOTIFY)
-- =====================================================
\echo 'TEST 11: Rule DELETE (via LISTEN/NOTIFY)'
\echo '-----------------------------------------------'

\echo 'Before delete: Rule 9001 exists'
SELECT 'DB Check:' as test, COUNT(*) as rule_exists 
FROM rules 
WHERE id = 9001;

DELETE FROM rules WHERE id = 9001;

\echo 'After delete: Rule 9001 should not exist'
SELECT 'DB Check:' as test, COUNT(*) as rule_exists 
FROM rules 
WHERE id = 9001;

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 12: Profile with complex domains
-- =====================================================
\echo 'TEST 12: Profile with complex JSON domains'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9008, 'test8@cache.test', 1, NOW(), NOW());

INSERT INTO profiles (id, name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (9007, 'Complex Domains Profile', 
        '[{"id":"uuid1","name":"google.com"},{"id":"uuid2","name":"github.com"},{"id":"uuid3","name":"stackoverflow.com"}]'::jsonb, 
        1, 1, true, NOW(), NOW());

INSERT INTO sessions (id, title, start_time, end_time, student_id, school_id, grade, profile_id, inserted_at, updated_at)
VALUES (9008, 'Complex Profile Session',
        NOW() - INTERVAL '5 minutes',
        NOW() + INTERVAL '30 minutes',
        9008, 1, 10, 9007, NOW(), NOW());

\echo 'Expected: Profile with 3 domains'
SELECT 'DB Check:' as test, jsonb_array_length(domains) as domain_count 
FROM profiles 
WHERE id = 9007;

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- TEST 13: Student without active sessions or rules
-- =====================================================
\echo 'TEST 13: Student with no active profiles'
\echo '-----------------------------------------------'

INSERT INTO students (id, feide_email, school_id, inserted_at, updated_at)
VALUES (9009, 'test9@cache.test', 1, NOW(), NOW());

\echo 'Expected: 0 active sessions, 0 applicable rules'
SELECT 'DB Check:' as test, COUNT(*) as active_sessions 
FROM sessions s
JOIN students st ON s.student_id = st.id
WHERE st.id = 9009 
  AND s.start_time <= NOW() 
  AND s.end_time >= NOW();

SELECT pg_sleep(0.5);
\echo ''

-- =====================================================
-- SUMMARY OF TEST DATA
-- =====================================================
\echo ''
\echo '========================================='
\echo 'TEST DATA SUMMARY'
\echo '========================================='

SELECT 'Students' as entity, COUNT(*) as count FROM students WHERE id >= 9000
UNION ALL
SELECT 'Profiles', COUNT(*) FROM profiles WHERE id >= 9000
UNION ALL
SELECT 'Sessions', COUNT(*) FROM sessions WHERE id >= 9000
UNION ALL
SELECT 'Rules', COUNT(*) FROM rules WHERE id >= 9000;

\echo ''
\echo '========================================='
\echo 'Test data creation complete!'
\echo 'Now run: bash database/verify_cache.sh'
\echo '========================================='
