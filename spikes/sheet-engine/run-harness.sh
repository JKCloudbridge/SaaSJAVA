#!/bin/sh
# POC 2 / POC 1 harness: compiles src/harness against the platform classes and runs one class.
# usage: sh run-harness.sh <ClassName> [args]   (from spikes/sheet-engine; the platform and its tests must have been compiled once)
CPFILE="${APP_TEST_CP:?set APP_TEST_CP to a file holding the test classpath of platform-app (mvnw -pl platform-app dependency:build-classpath)}"
CP="target/harness;target/classes;target/lib/*;../../platform-app/target/classes;../../platform-shared-kernel/target/classes;../../platform-app/target/test-classes;../../platform-api-contract/src/main/resources;$(cat "$CPFILE")"
rm -rf target/harness && mkdir -p target/harness
javac --release 25 -proc:none -d target/harness -cp "$CP" $(find src/harness/java -name "*.java") || exit 1
CLASS="$1"; shift
exec java -cp "$CP" "$CLASS" "$@"
