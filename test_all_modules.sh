#!/usr/bin/env bash
# test_all_modules.sh - Master test runner for the complete Antigravity v2 modular project.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "\n${BLUE}================================================================${NC}"
echo -e "${BLUE}  ANTIGRAVITY V2 - FULL TEST SUITE                              ${NC}"
echo -e "${BLUE}================================================================${NC}\n"

echo -e "${BLUE}>>> [1/11] Checking version consistency...${NC}"
python3 "$PROJECT_ROOT/modules/builder/check_versions.py"

echo -e "\n${BLUE}>>> [2/11] Testing modules/core...${NC}"
bash "$PROJECT_ROOT/modules/core/tests/run_all.sh"

echo -e "\n${BLUE}>>> [3/11] Testing modules/patches...${NC}"
bash "$PROJECT_ROOT/modules/patches/tests/run_all.sh"

echo -e "\n${BLUE}>>> [4/11] Testing modules/io...${NC}"
bash "$PROJECT_ROOT/modules/io/tests/run_all.sh"

echo -e "\n${BLUE}>>> [5/11] Testing modules/env...${NC}"
bash "$PROJECT_ROOT/modules/env/tests/run_all.sh"

echo -e "\n${BLUE}>>> [6/11] Testing modules/builder...${NC}"
bash "$PROJECT_ROOT/modules/builder/tests/run_all.sh"

echo -e "\n${BLUE}>>> [7/11] Testing env/tests unit suite...${NC}"
python3 -B -m unittest discover -s "$PROJECT_ROOT/env/tests" -v

echo -e "\n${BLUE}>>> [8/11] Building env assets and checking invariants...${NC}"
bash "$PROJECT_ROOT/env/build.sh" assets
bash "$PROJECT_ROOT/env/tests/invariant"

echo -e "\n${BLUE}>>> [9/11] Building web bundle and running web tests...${NC}"
bash "$PROJECT_ROOT/web/build.sh"
bash "$PROJECT_ROOT/web/tests/selftest.sh"
if command -v node >/dev/null 2>&1; then
    node "$PROJECT_ROOT/web/tests/test_web_patches.mjs"
else
    echo "[SKIP] node not found; skipping web/tests/test_web_patches.mjs"
fi

echo -e "\n${BLUE}>>> [10/11] Running Java unit tests...${NC}"
bash "$PROJECT_ROOT/app/src/test/run_java_tests.sh"

echo -e "\n${BLUE}>>> [11/11] Running Live E2E Server & Runtime Test...${NC}"
bash "$PROJECT_ROOT/modules/runner/test_e2e.sh"

echo -e "\n${GREEN}================================================================${NC}"
echo -e "${GREEN}  ALL SUITES IN ANTIGRAVITY V2 FULLY VERIFIED AND PASSING!      ${NC}"
echo -e "${GREEN}================================================================${NC}\n"
