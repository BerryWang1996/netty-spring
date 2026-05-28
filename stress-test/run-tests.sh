#!/bin/bash
# ===================================================================
#  Netty-Spring WebSocket Stress Test Runner
#  Simulates a 2C4G (2 CPU, 4GB RAM) server environment via Docker
# ===================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Check if the demo jar exists
JAR_FILE="demo-netty-web-spring-boot-starter-1.5.1.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Copying demo jar from build output..."
    cp ../demo-netty-web-spring-boot-starter/target/$JAR_FILE . 2>/dev/null || {
        echo "ERROR: $JAR_FILE not found. Build the project first:"
        echo "  cd .. && mvn clean package -DskipTests"
        exit 1
    }
fi

echo ""
echo "=============================================="
echo "  Building Docker images..."
echo "=============================================="
docker compose build

echo ""
echo "=============================================="
echo "  Starting server (2C4G limits)..."
echo "=============================================="
docker compose up -d server
echo "Waiting for server health check..."
docker compose exec server sh -c 'until wget -qO- http://localhost:8080/ 2>/dev/null; do sleep 1; done' 2>/dev/null
echo "Server is ready!"

echo ""
echo "=============================================="
echo "  Test 1: WebSocket Connection Stress Test"
echo "  (Finding max concurrent connections)"
echo "=============================================="
docker compose run --rm client node ws-connection-test.js \
    --host=server --port=8080 \
    --max=50000 --batch=200 --delay=50 --keepalive=30

echo ""
echo "=============================================="
echo "  Restarting server for clean state..."
echo "=============================================="
docker compose restart server
sleep 10

echo ""
echo "=============================================="
echo "  Test 2: IM Chat Stress Test"
echo "  (Measuring throughput & latency)"
echo "=============================================="
docker compose run --rm client node ws-chat-test.js \
    --host=server --port=8080 \
    --users=2000 --batch=100 --msgrate=1000 --duration=60 --pm-ratio=0.3

echo ""
echo "=============================================="
echo "  Cleanup"
echo "=============================================="
docker compose down

echo ""
echo "Done! Check the results above."
