#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
"$PROJECT_DIR/build.sh"
exec java -jar "$PROJECT_DIR/dist/fretflow-ai.jar" "$@"
