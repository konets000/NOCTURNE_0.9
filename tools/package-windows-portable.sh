#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DICT_SOURCE="${1:-$ROOT/app/build/generated/nocturne/assets/dictionary}"
OUTPUT_ROOT="${2:-$ROOT/build/ci-artifacts}"
VERSION="0.8.1"
PACKAGE_NAME="NOCTURNE-Windows-Portable-${VERSION}"
STAGING="$OUTPUT_ROOT/$PACKAGE_NAME"
ARCHIVE="$OUTPUT_ROOT/$PACKAGE_NAME.zip"

required=(
  cc-cedict.txt.gz
  unihan-characters.json.gz
  segmentation.txt.gz
  manifest.json
  NOTICE.txt
)
for file in "${required[@]}"; do
  if [[ ! -s "$DICT_SOURCE/$file" ]]; then
    echo "Missing or empty dictionary asset: $DICT_SOURCE/$file" >&2
    exit 2
  fi
done

rm -rf "$STAGING"
mkdir -p "$STAGING/dictionary" "$OUTPUT_ROOT"
cp "$ROOT/app/src/main/assets/index.html" "$STAGING/index.html"
cp "$ROOT/app/src/main/assets/app.js" "$STAGING/app.js"
cp "$ROOT/desktop/start-nocturne.ps1" "$STAGING/start-nocturne.ps1"
cp "$ROOT/desktop/start-nocturne.bat" "$STAGING/start-nocturne.bat"
cp -R "$ROOT/samples" "$STAGING/samples"
cp "$DICT_SOURCE"/* "$STAGING/dictionary/"
cat > "$STAGING/START-HERE.txt" <<'TEXT'
NOCTURNE WINDOWS PORTABLE EDITION

1. Extract this folder to a normal writable folder on your PC.
2. Double-click start-nocturne.bat.
3. Optionally import samples/bilingual-filter-demo.srt to confirm the Chinese-only subtitle filter.
4. Keep the PowerShell console open while NOCTURNE is running.
5. Import your SRT, then select its matching audio file.

No install, Python, Node, or network service is required. The launcher serves the app locally on 127.0.0.1 only.
This portable package stores sessions and imported dictionary data in the local browser profile. It does not upload user content.
TEXT
cat > "$STAGING/BUILD-INFO.txt" <<TEXT
NOCTURNE version: $VERSION
Built by: NOCTURNE CI — test and build workflow
Dictionary manifest: dictionary/manifest.json
Runtime: local-only PowerShell loopback server + Microsoft Edge app window
TEXT
rm -f "$ARCHIVE"
(
  cd "$OUTPUT_ROOT"
  zip -qr "$(basename "$ARCHIVE")" "$PACKAGE_NAME"
)
echo "Windows portable package created: $ARCHIVE"
