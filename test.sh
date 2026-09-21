#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
cd "$PROJECT_DIR"

rm -rf "$BUILD_DIR/test-classes"
mkdir -p "$BUILD_DIR/test-classes"
find src/main/java src/test/java -name '*.java' | sort > "$BUILD_DIR/test-sources.txt"
javac --release 17 -encoding UTF-8 -d "$BUILD_DIR/test-classes" @"$BUILD_DIR/test-sources.txt"

exec java -ea -cp "$BUILD_DIR/test-classes" dev.fretflow.AllTests
