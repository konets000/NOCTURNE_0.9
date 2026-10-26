#!/usr/bin/env sh
# Runs every test that works without Gradle, the Android SDK, or network access:
#   1. Java packager self-test (fixtures, pin checks, Unihan property regression, read-back verification)
#   2. Generates a dictionary with the packager and checks it with the app's own JavaScript parsers
#   3. MainActivity compiles against Android API stubs (catches wrong names and types)
#   4. Node smoke test (dictionary and segmentation behaviour, Windows launcher and public CI build structure)
#   5. Portable Windows ZIP smoke test using the synthetic dictionary fixture
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/tests"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

if command -v javac >/dev/null 2>&1; then
  JAVAC="javac"
else
  JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
fi

$JAVAC -Xlint:all -d "$OUT/classes" \
  "$ROOT/buildSrc/src/main/java/com/nocturne/build/DictionaryPackager.java" \
  "$ROOT/tests/dictionary/PackagerSelfTest.java" \
  "$ROOT/tests/dictionary/EmitFixture.java"

echo "== 1. packager self-test"
java -ea -cp "$OUT/classes" com.nocturne.build.PackagerSelfTest

echo "== 2. cross-language contract"
java -cp "$OUT/classes" com.nocturne.build.EmitFixture "$OUT/contract" >/dev/null
node "$ROOT/tests/dictionary/contract-check.cjs" "$OUT/contract/emit/out/dictionary"

echo "== 3. MainActivity compiles against the Android/AndroidX API stubs (signatures only, not behaviour)"
mkdir -p "$OUT/activity"
$JAVAC -Xlint:all -d "$OUT/activity" \
  $(find "$ROOT/tests/android-stubs" -name '*.java') \
  "$ROOT/app/src/main/java/com/nocturne/app/MainActivity.java"
echo "ok - MainActivity compiles with no warnings"

echo "== 4. app smoke test"
node "$ROOT/tests/app-smoke.test.cjs"

if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  echo "== 5. portable Windows package smoke test"
  PACKAGE_OUT="$OUT/windows-package"
  bash "$ROOT/tools/package-windows-portable.sh" "$OUT/contract/emit/out/dictionary" "$PACKAGE_OUT" >/dev/null
  ARCHIVE="$PACKAGE_OUT/NOCTURNE-Windows-Portable-0.8.1.zip"
  unzip -t "$ARCHIVE" >/dev/null
  unzip -Z1 "$ARCHIVE" | grep -q '/start-nocturne.ps1$'
  unzip -Z1 "$ARCHIVE" | grep -q '/app.js$'
  unzip -Z1 "$ARCHIVE" | grep -q '/dictionary/manifest.json$'
  echo "ok - portable Windows package contains launcher, HTML and dictionary assets"
fi

echo "ALL OFFLINE TESTS PASSED"
