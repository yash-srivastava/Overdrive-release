#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <jdk-directory> <json.jar> <scratch-directory>" >&2
    exit 2
fi
TEST_JDK=$1
TEST_JSON=$2
TEST_SCRATCH=$3
mkdir -p "$TEST_SCRATCH"
python3 "$SCRIPT_DIR/prepare.py" "$REPO_DIR" "$TEST_SCRATCH/src"
find "$TEST_SCRATCH/src" -name '*.java' > "$TEST_SCRATCH/sources.txt"
mkdir -p "$TEST_SCRATCH/classes"
"$TEST_JDK/bin/javac" -cp "$TEST_JSON" -d "$TEST_SCRATCH/classes" @"$TEST_SCRATCH/sources.txt"
"$TEST_JDK/bin/java" -cp "$TEST_JSON:$TEST_SCRATCH/classes" com.overdrive.app.server.BrowserOriginTest
