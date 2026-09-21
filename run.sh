#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
cd "$PROJECT_DIR"

mkdir -p "$BUILD_DIR/classes"
find src/main/java -name '*.java' | sort > "$BUILD_DIR/main-sources.txt"
javac --release 17 -encoding UTF-8 -d "$BUILD_DIR/classes" @"$BUILD_DIR/main-sources.txt"
cp -R "$PROJECT_DIR/src/main/resources/." "$BUILD_DIR/classes/"

exec java -cp "$BUILD_DIR/classes" dev.fretflow.Main "$@"
