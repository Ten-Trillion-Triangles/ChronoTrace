#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEMO_DIR="$PROJECT_ROOT/samples/chrono-demo"
GRADLEW="$PROJECT_ROOT/gradlew"

echo "=== ChronoTrace End-to-End Demo ==="
echo ""

# Step 1: Check/start docker-compose
echo "[1/5] Starting services with docker-compose..."
cd "$PROJECT_ROOT"

if docker compose ps 2>/dev/null | grep -q "Up"; then
    echo "  Services already running."
else
    echo "  Starting docker-compose services..."
    docker compose up -d
    echo "  Waiting for services to start..."
    sleep 5
fi

# Step 2: Wait for server to be healthy
echo "[2/5] Waiting for ChronoTrace server to be healthy..."
MAX_RETRIES=30
RETRY_COUNT=0
until curl -sf http://localhost:8081/health > /dev/null 2>&1; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo "  ERROR: Server failed to become healthy after ${MAX_RETRIES} retries."
        docker compose logs chronotrace-server 2>/dev/null || true
        exit 1
    fi
    echo "  Waiting... (${RETRY_COUNT}/${MAX_RETRIES})"
    sleep 2
done
echo "  Server is healthy."

# Step 3: Compile sample app and capture instrumentation output
echo "[3/5] Compiling ChronoTrace demo application..."
echo "  Running: ./gradlew compileKotlin --info"
echo ""

# Run gradle from the demo directory (it has its own settings.gradle.kts)
GRADLE_OUTPUT=$("$GRADLEW" -p "$DEMO_DIR" compileKotlin --info 2>&1 || true)

# Extract and display ChronoTrace instrumentation lines
echo "  --- Instrumentation Output ---"
INST_LINES=$(echo "$GRADLE_OUTPUT" | grep -E "ChronoTrace:.*instrumented" || true)
if [ -n "$INST_LINES" ]; then
    echo "$INST_LINES"
else
    echo "  (No 'ChronoTrace: X functions instrumented' lines found)"
    # Show daemon-related lines as fallback
    echo "  Showing kotlin daemon log entries (if any):"
    echo "$GRADLE_OUTPUT" | grep -E "kotlin-daemon|Kotlin daemon" | head -3 || true
fi
echo ""

# Show any build errors
ERRORS=$(echo "$GRADLE_OUTPUT" | grep -iE "error:|exception:|e: " | grep -v "Caused by:" | head -5 || true)
if [ -n "$ERRORS" ]; then
    echo "  --- Build Errors ---"
    echo "$ERRORS"
    echo ""
fi

# Step 4: Run the demo application
echo "[4/5] Running demo application..."
echo ""

DEMO_OUTPUT=$("$GRADLEW" -p "$DEMO_DIR" run 2>&1 || true)
echo "$DEMO_OUTPUT" | head -30 || echo "  (Run may have issues - see full output above)"
echo ""

# Step 5: Query the server to verify data was ingested
echo "[5/5] Verifying data ingestion via query API..."
echo ""

# Query recent logs
echo "  Recent logs:"
LOGS_RESPONSE=$(curl -s -H "X-Api-Key: test-api-key" "http://localhost:8081/api/v1/logs/search?appId=chrono-demo&limit=5" 2>/dev/null || echo "{}")
echo "$LOGS_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "  Raw: $LOGS_RESPONSE"

echo ""
echo "  Recent traces:"
TRACES_RESPONSE=$(curl -s -H "X-Api-Key: test-api-key" "http://localhost:8081/api/v1/traces?limit=3" 2>/dev/null || echo "{}")
echo "$TRACES_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "  Raw: $TRACES_RESPONSE"

echo ""
echo "=== Demo Complete ==="
echo ""
echo "To view full server logs: docker compose -f $PROJECT_ROOT/docker-compose.yml logs chronotrace-server"
echo "To stop services: docker compose -f $PROJECT_ROOT/docker-compose.yml down"