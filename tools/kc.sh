#!/usr/bin/env bash
# Quick Kotlin compile of the app sources (aarch64 host, no Gradle/aapt).
# Usage: tools/kc.sh <out-dir> [files...]
set -euo pipefail

ROOT=/tmp/opencode
LIBS=$ROOT/dl/libs
ANDROID_JAR=$ROOT/dl/android-34/android.jar

KCP="$LIBS/kotlin-compiler-embeddable-2.0.21.jar:$LIBS/kotlin-stdlib-2.0.21.jar:$LIBS/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIBS/trove4j-1.0.20200330.jar:$LIBS/kotlin-script-runtime-2.0.21.jar:$LIBS/kotlin-reflect-2.0.21.jar:$LIBS/kotlin-daemon-embeddable-2.0.21.jar:$LIBS/annotations-24.1.0.jar"

DCP="$ANDROID_JAR:$LIBS/kotlin-stdlib-2.0.21.jar:$LIBS/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIBS/kotlinx-coroutines-android-1.8.1.jar:$LIBS/jsoup-1.17.2.jar"

OUT="${1:-/tmp/opencode/classes}"
shift || true

if [ "$#" -eq 0 ]; then
  # shellcheck disable=SC2046
  set -- $(find "$(dirname "$0")/../app/src/main/java" -name '*.kt' | sort)
fi

mkdir -p "$OUT"

exec java -Xmx192m -Xss2m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
  -XX:ReservedCodeCacheSize=32m -Djava.awt.headless=true \
  -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 11 \
  -classpath "$DCP" \
  -d "$OUT" "$@"
