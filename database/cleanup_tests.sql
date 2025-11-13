-- =========================================
-- CLEANUP TEST DATA
-- =========================================
-- Removes all test data (IDs >= 9000) to allow repeatable testing

\echo '========================================='
\echo 'CLEANING UP TEST DATA'
\echo '========================================='

-- Delete test data in correct order (respecting foreign keys)
DELETE FROM sessions WHERE id >= 9000;
DELETE FROM rules WHERE id >= 9000;
DELETE FROM profiles WHERE id >= 9000;
DELETE FROM students WHERE id >= 9000;

-- Verify cleanup
\echo ''
\echo 'Verification: All test data should be removed'
\echo '---------------------------------------------'
SELECT
    (SELECT COUNT(*) FROM students WHERE id >= 9000) as test_students,
    (SELECT COUNT(*) FROM profiles WHERE id >= 9000) as test_profiles,
    (SELECT COUNT(*) FROM rules WHERE id >= 9000) as test_rules,
    (SELECT COUNT(*) FROM sessions WHERE id >= 9000) as test_sessions;

\echo ''
\echo '✅ Cleanup completed!'
\echo ''
