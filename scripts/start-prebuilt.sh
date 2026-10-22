#!/usr/bin/env bash
# Run the platform from prebuilt jars and web/dist. No Maven and no npm.
# Prerequisites: Java 21+ and Python 3. YugabyteDB is required for a real sign-in.
# The Demo button works from the compiled UI alone.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p logs

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java 21+ is required."
  exit 1
fi
java -version 2>&1 | head -1

start_jar() {
  local name="$1"
  local jar="$2"
  if [[ ! -f "$jar" ]]; then
    echo "ERROR: Missing $jar"
    echo "This file is part of the repository. Check the checkout, or run ./scripts/download-prebuilt.sh if GitHub release downloads are allowed."
    exit 1
  fi
  echo "Starting $name"
  nohup java -jar "$jar" >"logs/${name}.log" 2>&1 &
  echo $! >"logs/${name}.pid"
}

start_jar metadata-api metadata-api/target/metadata-api-1.0.0.jar
start_jar login-api login-api/target/login-api-1.0.0.jar
start_jar execution-api execution-api/target/execution-api-1.0.0.jar
start_jar intake-api intake-api/target/intake-api-1.0.0.jar
start_jar metadata-mcp metadata-mcp/target/metadata-mcp-1.0.0.jar

if [[ ! -f web/dist/index.html ]]; then
  echo "ERROR: Missing web/dist. This directory is part of the repository."
  exit 1
fi

UI_PORT="${UI_PORT:-5173}"
echo "Starting UI on :$UI_PORT"
UI_PORT="$UI_PORT" nohup python3 scripts/serve-ui.py >logs/web.log 2>&1 &
echo $! >logs/web.pid

sleep 2
echo ""
echo "Online Data Platform started without compiling."
echo "  UI:            http://localhost:${UI_PORT}"
echo "  Catalog API:   http://localhost:4000"
echo "  Login API:     http://localhost:4010"
echo "  Intake API:    http://localhost:4020"
echo "  Execution API: http://localhost:4030"
echo "  MCP:           http://localhost:4001"
echo ""
echo "Use the Demo button for the sample dataset. It does not call the APIs."
echo "Stop with: ./scripts/stop-prebuilt.sh"
echo "Logs: logs/*.log"
