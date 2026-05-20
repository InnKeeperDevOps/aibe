#!/bin/bash
# Tests for the site-manager-dev service in docker-compose.yml
# Run with: bash src/test/bash/test_docker_compose.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

PASS=0
FAIL=0

assert_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo "  PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc"
        echo "        expected: '$expected'"
        echo "        actual:   '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

assert_match() {
    local desc="$1" pattern="$2" actual="$3"
    if echo "$actual" | grep -qE "$pattern"; then
        echo "  PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc"
        echo "        pattern: '$pattern'"
        echo "        actual:  '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

assert_contains() {
    local desc="$1" needle="$2"
    if grep -qF "$needle" "$COMPOSE_FILE"; then
        echo "  PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc — '$needle' not found in $COMPOSE_FILE"
        FAIL=$((FAIL + 1))
    fi
}

assert_not_contains() {
    local desc="$1" needle="$2" context="$3"
    # Only check within the context section if provided
    if [ -n "$context" ]; then
        local section
        section=$(awk "/^  $context:/,/^  [a-z]/" "$COMPOSE_FILE")
        if echo "$section" | grep -qF "$needle"; then
            echo "  FAIL: $desc — '$needle' found in $context section but should not be changed"
            FAIL=$((FAIL + 1))
        else
            echo "  PASS: $desc"
            PASS=$((PASS + 1))
        fi
    fi
}

# ---------------------------------------------------------------------------
echo "=== docker-compose.yml exists ==="

if [ -f "$COMPOSE_FILE" ]; then
    echo "  PASS: docker-compose.yml exists"
    PASS=$((PASS + 1))
else
    echo "  FAIL: docker-compose.yml not found at $COMPOSE_FILE"
    FAIL=$((FAIL + 1))
    echo "Results: $PASS passed, $FAIL failed"
    exit 1
fi

# ---------------------------------------------------------------------------
echo "=== site-manager-dev service defined ==="

assert_contains "site-manager-dev service exists" "site-manager-dev:"
assert_contains "uses gradle:8.14-jdk17 image" "image: gradle:8.14-jdk17"
assert_contains "working_dir is /app" "working_dir: /app"

# ---------------------------------------------------------------------------
echo "=== site-manager-dev volume mounts ==="

assert_contains "repo root bind-mounted as /app" ".:/app"
assert_contains "app-data volume mounted" "app-data:/data"
assert_contains "SSH deploy key mounted read-only" "id_deploy:ro"
assert_contains "SSH_DEPLOY_KEY_PATH variable with fallback" 'SSH_DEPLOY_KEY_PATH:-'

# ---------------------------------------------------------------------------
echo "=== site-manager-dev environment variables ==="

assert_contains "SPRING_PROFILES_ACTIVE set to docker" "SPRING_PROFILES_ACTIVE=docker"
assert_contains "ANTHROPIC_API_KEY env var present" "ANTHROPIC_API_KEY="
assert_contains "TARGET_REPO_URL env var present" "TARGET_REPO_URL="
assert_contains "GIT_SSH_KEY_PATH env var present" "GIT_SSH_KEY_PATH="
assert_contains "SPRING_DATASOURCE_URL env var present" "SPRING_DATASOURCE_URL=jdbc:sqlite:/data/aibe.db"
assert_contains "SPRING_DATASOURCE_DRIVER_CLASS_NAME present" "SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.sqlite.JDBC"
assert_contains "SPRING_JPA_DATABASE_PLATFORM present" "SPRING_JPA_DATABASE_PLATFORM=org.hibernate.community.dialect.SQLiteDialect"
assert_contains "GIT_SSH_COMMAND with id_deploy key" "GIT_SSH_COMMAND=ssh -i /root/.ssh/id_deploy"
assert_contains "AUTO_UPDATE_BRANCH with main default" "AUTO_UPDATE_BRANCH=\${AUTO_UPDATE_BRANCH:-main}"
assert_contains "AUTO_UPDATE_INTERVAL with 60 default" "AUTO_UPDATE_INTERVAL=\${AUTO_UPDATE_INTERVAL:-60}"

# ---------------------------------------------------------------------------
echo "=== site-manager-dev command and ports ==="

assert_contains "command is ./auto-update.sh" "command: ./auto-update.sh"
assert_contains "port 8081 mapped to container 8080" '"8081:8080"'
assert_contains "restart: unless-stopped" "restart: unless-stopped"

# ---------------------------------------------------------------------------
echo "=== site-manager-dev SSH key comment ==="

assert_contains "comment about read-only deploy key" "read-only deploy key"

# ---------------------------------------------------------------------------
echo "=== existing site-manager service unchanged ==="

# Check original service still uses build: . and port 8080:8080
assert_contains "original service still uses build: ." "build: ."
assert_contains "original service still has 8080:8080" '"8080:8080"'

# Verify the original service is not using gradle image
SITE_MANAGER_SECTION=$(awk '/^  site-manager:/{found=1} found && /^  [a-z]/ && !/^  site-manager:/{exit} found{print}' "$COMPOSE_FILE")
if echo "$SITE_MANAGER_SECTION" | grep -q "image: gradle"; then
    echo "  FAIL: original site-manager service should not use gradle image"
    FAIL=$((FAIL + 1))
else
    echo "  PASS: original site-manager service does not use gradle image"
    PASS=$((PASS + 1))
fi

# ---------------------------------------------------------------------------
echo "=== volumes block still present ==="

assert_contains "app-data volume declared" "app-data:"
assert_contains "workspace volume declared" "workspace:"

# ---------------------------------------------------------------------------
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
