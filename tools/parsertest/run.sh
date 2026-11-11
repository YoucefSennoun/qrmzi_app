#!/usr/bin/env bash
# JVM regression test for the HTML parsers.
#
# Runs the real data-layer code against the fixtures captured from qrmzi.tv in
# /tmp/opencode/qrmzi-evidence, so the selectors can be re-checked without a
# device. Not part of the APK.
#
# Usage: tools/parsertest/run.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/tv/qrmzi/client"

SDK=${QRMZI_SDK:-/tmp/opencode/dl}
LIBS=$SDK/libs
ANDROID_JAR=$SDK/android-34/android.jar
OUT=${QRMZI_TEST_OUT:-/tmp/opencode/pt}

for f in "$ANDROID_JAR" "$LIBS/jsoup-1.17.2.jar"; do
  [ -f "$f" ] || { echo "missing test input: $f" >&2; exit 1; }
done

KCP="$LIBS/kotlin-compiler-embeddable-2.0.21.jar:$LIBS/kotlin-stdlib-2.0.21.jar:$LIBS/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIBS/trove4j-1.0.20200330.jar:$LIBS/kotlin-script-runtime-2.0.21.jar:$LIBS/kotlin-reflect-2.0.21.jar:$LIBS/kotlin-daemon-embeddable-2.0.21.jar:$LIBS/annotations-24.1.0.jar"
DCP="$ANDROID_JAR:$LIBS/kotlin-stdlib-2.0.21.jar:$LIBS/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIBS/jsoup-1.17.2.jar"

rm -rf "$OUT"
mkdir -p "$OUT"

java -Xmx192m -Xss2m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
  -XX:ReservedCodeCacheSize=32m -Djava.awt.headless=true \
  -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 11 \
  -classpath "$DCP" -d "$OUT" \
  "$SRC/data/Models.kt" "$SRC/data/Parsers.kt" "$SRC/data/Http.kt" \
  "$SRC/data/Store.kt" "$SRC/data/Playback.kt" "$SRC/data/Repo.kt" \
  "$SRC/data/Diag.kt" "$SRC/data/Filter.kt" "$SRC/data/NavPolicy.kt" \
  "$SRC/util/Arabic.kt" \
  "$HERE/ParserTest.kt" 2>&1 | grep -v 'warning:' || true

java -Xmx192m -XX:+UseSerialGC -cp "$OUT:$DCP" ParserTestKt
