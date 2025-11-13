#!/bin/bash

# =========================================
# CACHE VERIFICATION SCRIPT
# =========================================
# Tests cache API responses against expected database state

set -e

BASE_URL="http://localhost:8082"
PASS_COUNT=0
FAIL_COUNT=0

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================="
echo "CACHE VERIFICATION TEST SUITE"
echo "========================================="
echo ""

# Helper function to test endpoint
test_cache() {
    local test_name="$1"
    local endpoint="$2"
    local expected="$3"
    local description="$4"

    echo "Test: $test_name"
    echo "  Description: $description"

    # Make API call
    response=$(curl -s "$BASE_URL$endpoint")

    # Check if response contains expected value
    if echo "$response" | grep -q "$expected"; then
        echo -e "  ${GREEN}✓ PASS${NC}"
        ((PASS_COUNT++))
    else
        echo -e "  ${RED}✗ FAIL${NC}"
        echo "  Expected to find: $expected"
        echo "  Got: $response"
        ((FAIL_COUNT++))
    fi
    echo ""
}

# Test cache stats endpoint
test_cache "Cache Stats" \
    "/cache/stats" \
    "studentsById" \
    "Cache should return statistics"

# Test 1: Active session - student should have active profile
test_cache "TEST 1: Active Session" \
    "/cache/profiles/active/test1@cache.test" \
    '"id":9001' \
    "Student test1@cache.test should have profile 9001 active"

# Test 2: Expired session - should have no active profiles
test_cache "TEST 2: Expired Session" \
    "/cache/profiles/active/test2@cache.test" \
    '"profiles":\[\]' \
    "Student test2@cache.test should have no active profiles (expired)"

# Test 3: Future session - should have no active profiles yet
test_cache "TEST 3: Future Session" \
    "/cache/profiles/active/test3@cache.test" \
    '"profiles":\[\]' \
    "Student test3@cache.test should have no active profiles (future)"

# Test 4: School-wide rule - student should get profile from school rule
test_cache "TEST 4: School Rule" \
    "/cache/profiles/active/test4@cache.test" \
    '"id":9002' \
    "Student test4@cache.test should have profile 9002 from school rule"

# Test 5: Grade-level rule - student in grade 9 should get profile
test_cache "TEST 5: Grade Rule" \
    "/cache/profiles/active/test5@cache.test" \
    '"id":9003' \
    "Student test5@cache.test should have profile 9003 from grade rule"

# Test 6: Student-specific rule - student should get their specific profile
test_cache "TEST 6: Student Rule" \
    "/cache/profiles/active/test6@cache.test" \
    '"id":9004' \
    "Student test6@cache.test should have profile 9004 from student rule"

# Test 7: Multiple active sessions - student should have profile from sessions
test_cache "TEST 7: Multiple Sessions" \
    "/cache/profiles/active/test7@cache.test" \
    '"id":9001' \
    "Student test7@cache.test should have profile 9001 from multiple active sessions"

# Test 8: Expired rule - student should have no active profiles
test_cache "TEST 8: Expired Rule" \
    "/cache/profiles/active/test8@cache.test" \
    '"profiles":\[\]' \
    "Student should have no active profiles (rule expired)"

# Test 10: Session UPDATE - profile should have changed to 9002
test_cache "TEST 10: Session Update" \
    "/cache/profiles/active/test1@cache.test" \
    '"id":9002' \
    "Session 9001 should now have profile 9002 after update"

# Test 12: Complex JSON domains - profile should be accessible
test_cache "TEST 12: Complex Domains" \
    "/cache/profiles/active/test8@cache.test" \
    '"id":9007' \
    "Student test8@cache.test should have profile 9007 with complex domains"

# Test 13: No active profiles - student should return empty
test_cache "TEST 13: No Active Profiles" \
    "/cache/profiles/active/test9@cache.test" \
    '"profiles":\[\]' \
    "Student test9@cache.test should have no active profiles"

# Test school rules retrieval
test_cache "School Rules Retrieval" \
    "/cache/rules/school" \
    "School rules:" \
    "Should be able to retrieve school rules"

# Test health endpoint
test_cache "Health Check" \
    "/health" \
    '"status":"ok"' \
    "Health endpoint should return OK"

# Test expand parameter (non-expanded)
test_cache "Expand Parameter False" \
    "/cache/profiles/active/test1@cache.test?expand=false" \
    '"profileIds":\[' \
    "Should return only profile IDs when expand=false"

echo "========================================="
echo "TEST SUMMARY"
echo "========================================="
echo -e "${GREEN}Passed: $PASS_COUNT${NC}"
echo -e "${RED}Failed: $FAIL_COUNT${NC}"
echo "Total:  $((PASS_COUNT + FAIL_COUNT))"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}✗ Some tests failed!${NC}"
    exit 1
fi
