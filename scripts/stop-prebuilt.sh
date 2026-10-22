#!/usr/bin/env bash
# Stop processes started by scripts/start-prebuilt.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -d logs ]]; then
  echo "No logs/ directory. Nothing to stop."
  exit 0
fi

for pidfile in logs/*.pid; do
  [[ -f "$pidfile" ]] || continue
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  name="$(basename "$pidfile" .pid)"
  if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
    echo "Stopping $name (pid $pid)"
    kill "$pid" 2>/dev/null || true
  fi
  rm -f "$pidfile"
done

echo "Done."
