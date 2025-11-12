-- Test script to modify tables and verify LISTEN/NOTIFY cache updates
-- Run this while the Java application is running to see cache updates in real-time

\echo '========================================='
\echo 'TEST 1: Update a student email'
\echo '========================================='
UPDATE students
SET feide_email = 'updated_' || floor(random() * 1000)::text || '@test.com'
WHERE id = (SELECT id FROM students LIMIT 1)
RETURNING id, feide_email;

\echo ''
\echo 'Waiting 2 seconds for cache to update...'
SELECT pg_sleep(2);

\echo ''
\echo '========================================='
\echo 'TEST 2: Update a profile name'
\echo '========================================='
UPDATE profiles
SET name = 'Updated Profile ' || floor(random() * 1000)::text
WHERE id = (SELECT id FROM profiles LIMIT 1)
RETURNING id, name;

\echo ''
\echo 'Waiting 2 seconds for cache to update...'
SELECT pg_sleep(2);

\echo ''
\echo '========================================='
\echo 'TEST 3: Update a rule scope_value'
\echo '========================================='
UPDATE rules
SET scope_value = 'Updated-' || floor(random() * 1000)::text
WHERE id = (SELECT id FROM rules LIMIT 1)
RETURNING id, scope, scope_value;

\echo ''
\echo 'Waiting 2 seconds for cache to update...'
SELECT pg_sleep(2);

\echo ''
\echo '========================================='
\echo 'TEST 4: Update a session title'
\echo '========================================='
UPDATE sessions
SET title = 'Updated Session ' || floor(random() * 1000)::text
WHERE id = (SELECT id FROM sessions LIMIT 1)
RETURNING id, title, student_id;

\echo ''
\echo 'Waiting 2 seconds for cache to update...'
SELECT pg_sleep(2);

\echo ''
\echo '========================================='
\echo 'TEST 5: Insert a new profile'
\echo '========================================='
INSERT INTO profiles (name, domains, teacher_id, school_id, is_whitelist_url, inserted_at, updated_at)
VALUES (
    'Test Profile ' || floor(random() * 10000)::text,
    '["test.com", "example.com"]'::jsonb,
    (SELECT id FROM teachers LIMIT 1),
    (SELECT id FROM schools LIMIT 1),
    true,
    NOW(),
    NOW()
)
RETURNING id, name;

\echo ''
\echo 'Waiting 2 seconds for cache to update...'
SELECT pg_sleep(2);

\echo ''
\echo '========================================='
\echo 'TEST 6: Delete the test profile'
\echo '========================================='
DELETE FROM profiles
WHERE name LIKE 'Test Profile%'
RETURNING id, name;

\echo ''
\echo 'Waiting 2 seconds for cache to update...'
SELECT pg_sleep(2);

\echo ''
\echo '========================================='
\echo 'All tests completed!'
\echo 'Check the Java application logs for cache update messages'
\echo '========================================='
