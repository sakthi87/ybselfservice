#!/usr/bin/env bash
# Download prebuilt API jars and the compiled UI from the GitHub Release.
# Source is cloned separately. Nothing here compiles.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO="${YB_RELEASE_REPO:-sakthi87/ybselfservice}"
TAG="${YB_RELEASE_TAG:-v1.0.0}"
BASE="https://github.com/${REPO}/releases/download/${TAG}"

mkdir -p \
  metadata-api/target \
  login-api/target \
  intake-api/target \
  execution-api/target \
  metadata-mcp/target \
  web

download() {
  local url="$1"
  local out="$2"
  echo "Downloading $(basename "$out") ..."
  curl -fL --retry 3 --retry-delay 2 -o "$out" "$url"
  echo "  -> $out ($(du -h "$out" | awk '{print $1}'))"
}

download "${BASE}/metadata-api-1.0.0.jar" metadata-api/target/metadata-api-1.0.0.jar
download "${BASE}/login-api-1.0.0.jar" login-api/target/login-api-1.0.0.jar
download "${BASE}/intake-api-1.0.0.jar" intake-api/target/intake-api-1.0.0.jar
download "${BASE}/execution-api-1.0.0.jar" execution-api/target/execution-api-1.0.0.jar
download "${BASE}/metadata-mcp-1.0.0.jar" metadata-mcp/target/metadata-mcp-1.0.0.jar
download "${BASE}/web-dist.zip" /tmp/ybselfservice-web-dist.zip
rm -rf web/dist
unzip -qo /tmp/ybselfservice-web-dist.zip -d web
rm -f /tmp/ybselfservice-web-dist.zip
echo "Extracted web/dist"

echo ""
echo "Artifacts ready. Start with:"
echo "  ./scripts/start-prebuilt.sh"
