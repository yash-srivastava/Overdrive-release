#!/bin/sh
# Requires a local JDK, Python 3 and an existing org.json jar.
# This runner never fetches dependencies. Supply a disposable writable output directory.
set -eu
if [ "$#" -ne 3 ]; then
    echo 'usage: run.sh JDK_DIRECTORY JSON_JAR OUTPUT_DIRECTORY' >&2
    exit 2
fi
suite=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$suite/../../.." && pwd)
jdk=$1
json_jar=$2
output=$3
mkdir -p "$output"
python3 "$suite/prepare.py" "$repo" "$output/generated"
find "$output/generated" -name '*.java' > "$output/sources.txt"
mkdir -p "$output/classes"
"$jdk/bin/javac" -encoding UTF-8 -cp "$json_jar" -d "$output/classes" @"$output/sources.txt"
"$jdk/bin/java" -Xms32m -Xmx128m -cp "$output/classes:$json_jar" com.overdrive.app.server.HttpBodyCompatibilityTest
