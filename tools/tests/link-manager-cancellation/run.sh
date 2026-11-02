#!/bin/sh
# Compile the actual production LinkManager with isolated, in-memory collaborators.
set -eu
if [ "$#" -ne 2 ]; then
    printf '%s\n' "Usage: sh $0 <dependency-classpath> <writable-build-root>" >&2
    exit 2
fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
TEST_ROOT="$ROOT/tools/tests/link-manager-cancellation"
mkdir -p "$2"
BUILD_DIR=$(mktemp -d "$2/link-manager-cancellation.XXXXXX")
mkdir "$BUILD_DIR/classes"
printf '%s\n' 'Compiling original LinkManager.java and OAuthApps.java with isolated test collaborators'
javac -encoding UTF-8 -cp "$1" -d "$BUILD_DIR/classes" \
    "$TEST_ROOT/src/com/overdrive/app/safekeep/"*.java \
    "$TEST_ROOT/fixtures/com/overdrive/app/safekeep/"*.java \
    "$TEST_ROOT/fixtures/com/overdrive/app/safekeep/net/"*.java \
    "$TEST_ROOT/fixtures/com/overdrive/app/logging/"*.java \
    "$TEST_ROOT/fixtures/com/overdrive/app/util/"*.java \
    "$ROOT/app/src/main/java/com/overdrive/app/safekeep/LinkManager.java" \
    "$ROOT/app/src/main/java/com/overdrive/app/safekeep/auth/OAuthApps.java"
java -ea -cp "$BUILD_DIR/classes:$1" org.junit.runner.JUnitCore \
    com.overdrive.app.safekeep.LinkManagerCancellationTest
