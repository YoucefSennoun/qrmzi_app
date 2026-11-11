#!/usr/bin/env bash
# End-to-end APK build for the Qrmzi TV client.
#
# The host is aarch64 (Termux) and the Google SDK build-tools are x86_64, so
# aapt/aapt2/zipalign cannot run here. Instead:
#   kotlinc  -> classes jar
#   D8       -> classes.dex (min-api 29)
#   python   -> binary AndroidManifest.xml + aligned zip
#   apksigner-> signed, installable APK
#
# Usage: tools/build.sh [--debug] [--unsigned]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/.." && pwd)"
BUILD="$ROOT_DIR/build"

SDK=/tmp/opencode/dl
LIBS=$SDK/libs
ANDROID_JAR=$SDK/android-34/android.jar
TOOLS=/tmp/opencode/bt/android-14/lib
D8_JAR=$TOOLS/d8.jar
APKSIGNER_JAR=$TOOLS/apksigner.jar

DEBUG=0
SIGN=1
for arg in "$@"; do
  case "$arg" in
    --debug) DEBUG=1 ;;
    --unsigned) SIGN=0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

for f in "$ANDROID_JAR" "$D8_JAR"; do
  [ -f "$f" ] || { echo "missing build input: $f" >&2; exit 1; }
done

KCP="$LIBS/kotlin-compiler-embeddable-2.0.21.jar:$LIBS/kotlin-stdlib-2.0.21.jar:$LIBS/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIBS/trove4j-1.0.20200330.jar:$LIBS/kotlin-script-runtime-2.0.21.jar:$LIBS/kotlin-reflect-2.0.21.jar:$LIBS/kotlin-daemon-embeddable-2.0.21.jar:$LIBS/annotations-24.1.0.jar"
DCP="$ANDROID_JAR:$LIBS/kotlin-stdlib-2.0.21.jar:$LIBS/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIBS/kotlinx-coroutines-android-1.8.1.jar:$LIBS/jsoup-1.17.2.jar"

PROGRAM_JARS=(
  "$LIBS/kotlin-stdlib-2.0.21.jar"
  "$LIBS/kotlinx-coroutines-core-jvm-1.8.1.jar"
  "$LIBS/kotlinx-coroutines-android-1.8.1.jar"
  "$LIBS/jsoup-1.17.2.jar"
)

rm -rf "$BUILD"
mkdir -p "$BUILD/dex"

echo "== 1/5 compile Kotlin =="
find "$ROOT_DIR/app/src/main/java" -name '*.kt' | sort > "$BUILD/sources.txt"
mapfile -t SOURCES < "$BUILD/sources.txt"
echo "   ${#SOURCES[@]} source files"
java -Xmx192m -Xss2m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
  -XX:ReservedCodeCacheSize=32m -Djava.awt.headless=true \
  -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 11 \
  -classpath "$DCP" \
  -d "$BUILD/classes.jar" \
  "${SOURCES[@]}" 2> "$BUILD/kotlinc.log"
grep -c 'warning:' "$BUILD/kotlinc.log" | sed 's/^/   warnings: /'
if grep -q 'error:' "$BUILD/kotlinc.log"; then
  echo "compile failed:" >&2
  grep 'error:' "$BUILD/kotlinc.log" >&2
  exit 1
fi

echo "== 2/5 dex (D8, min-api 29) =="
# R8 8.2 cannot parse Kotlin 2.0 metadata (harmless: it keeps the original
# bytes and only warns), so its output is kept in a log instead of the console.
java -Xmx384m -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 29 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  "$BUILD/classes.jar" "${PROGRAM_JARS[@]}" 2> "$BUILD/d8.log"
if grep -q '^Error\|Compilation failed\|Fatal' "$BUILD/d8.log"; then
  tail -40 "$BUILD/d8.log" >&2
  exit 1
fi
echo "   warnings: $(grep -c 'Warning: An error occurred when parsing kotlin metadata' "$BUILD/d8.log") (Kotlin-metadata warnings only)"
ls -l "$BUILD/dex" | sed 's/^/   /'

echo "== 3/5 binary manifest =="
MANIFEST_ARGS=()
[ "$DEBUG" -eq 1 ] && MANIFEST_ARGS+=(--debug)
python3 "$HERE/gen_manifest.py" "$BUILD/AndroidManifest.xml" "${MANIFEST_ARGS[@]}"

echo "== 4/5 package =="
python3 "$HERE/package_apk.py" \
  "$BUILD/AndroidManifest.xml" "$BUILD/dex" "$BUILD/qrmzi-unsigned.apk"

if [ "$SIGN" -eq 0 ]; then
  echo "== 5/5 skipped signing (--unsigned) =="
  exit 0
fi

echo "== 5/5 sign =="
[ -f "$APKSIGNER_JAR" ] || { echo "missing apksigner: $APKSIGNER_JAR" >&2; exit 1; }
KEYSTORE="$ROOT_DIR/keystore/qrmzi-release.jks"
STOREPASS="${QRMZI_STOREPASS:-qrmzi-tv-2026}"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  echo "   generating release keystore (keep it: updates need the same key)"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -alias qrmzi -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -dname "CN=Qrmzi TV, OU=Client, O=Qrmzi, L=Riyadh, C=SA" >/dev/null 2>&1
fi

java -jar "$APKSIGNER_JAR" sign \
  --ks "$KEYSTORE" --ks-key-alias qrmzi \
  --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" \
  --min-sdk-version 29 --v2-signing-enabled true \
  --out "$BUILD/qrmzi-tv.apk" "$BUILD/qrmzi-unsigned.apk"

java -jar "$APKSIGNER_JAR" verify --min-sdk-version 29 --verbose "$BUILD/qrmzi-tv.apk" \
  | sed 's/^/   /'

echo
echo "APK: $BUILD/qrmzi-tv.apk"
ls -l "$BUILD/qrmzi-tv.apk" | sed 's/^/   /'
sha256sum "$BUILD/qrmzi-tv.apk" | sed 's/^/   /'
