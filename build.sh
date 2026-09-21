#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLASSES_DIR="$PROJECT_DIR/build/classes"
DIST_DIR="$PROJECT_DIR/dist"
SOURCE_LIST="$PROJECT_DIR/build/main-sources.txt"

rm -rf "$CLASSES_DIR"
mkdir -p "$CLASSES_DIR" "$DIST_DIR"
cd "$PROJECT_DIR"
find src/main/java -name '*.java' | sort > "$SOURCE_LIST"
javac --release 17 -encoding UTF-8 -d "$CLASSES_DIR" @"$SOURCE_LIST"
jar --create --file "$DIST_DIR/fretflow-ai.jar" --main-class dev.fretflow.Main -C "$CLASSES_DIR" .

echo "Built $DIST_DIR/fretflow-ai.jar"
