# NOCTURNE

**Offline-first Mandarin listening with synchronized SRT subtitles.**

NOCTURNE is a local-first listening reader for Android and Windows. Import a Chinese `.srt` file and matching audio, follow synchronized subtitle highlighting, and tap words to explore segmentation, pinyin, and offline dictionary entries. Both editions share the same reader and generated dictionary assets.

> **Development preview · v0.8.1.** Stable releases are not available yet. Android-device and Windows-runtime validation remain outstanding. CI artifacts are for testing, not production use.

## Features

- Timestamp-preserving SRT import and synchronized subtitle highlighting.
- Local audio playback, seeking, playback-speed control, and sentence looping.
- Chinese word segmentation and tap-to-lookup.
- Offline definitions from CC-CEDICT and Unicode Unihan, plus a character-pinyin fallback.
- Local subtitle sessions and saved words.
- Android WebView app and portable Windows edition using Microsoft Edge.

## Privacy model

- The app does not upload subtitles, audio, or listening sessions; there is no account or cloud service.
- The Android app does not request the `INTERNET` permission. Files are selected using Android's file picker.
- The Windows launcher serves the app only on `127.0.0.1:18473`, not on the local network. Its Edge profile lives under `%LOCALAPPDATA%\NOCTURNE\BrowserProfile`.
- **Builds are not offline:** CI and first-time local builds download Gradle/Android dependencies and the pinned dictionary datasets.

These are source-level design claims, not an independent security audit. Review [SECURITY.md](SECURITY.md) and validate your target device before relying on the app.

## Get a development build

The GitHub Actions workflow runs tests, builds an Android debug APK, and packages a Windows portable ZIP. Artifacts are uploaded for pushes and manual runs, not pull requests, and are retained for 14 days. Open **Actions → CI — test and build**, then download the artifacts from the latest successful run.

### Android

1. Download `NOCTURNE-Android-debug-0.8.1.apk` to your device.
2. Open it from Downloads or Files and allow installation from that source if Android asks.
3. Launch NOCTURNE, import an SRT file, and select the matching audio.

CI uses an ephemeral debug signing key, so successive builds may not install as updates over an earlier APK. If Android refuses the install, uninstall the earlier debug build first. This APK is not verified for Google Play.

### Windows

1. Extract `NOCTURNE-Windows-Portable-0.8.1.zip` to a writable folder.
2. Double-click `start-nocturne.bat` and keep its console open while using the app.
3. Import an SRT file and matching audio. Synthetic examples are available in [`samples/`](samples/).

The portable build requires Windows PowerShell 5.1+ and Microsoft Edge. It does not need Python, Node.js, Java, or an installer. Closing the app window stops the local server.

## Build from source

### Prerequisites

- JDK 17 or newer.
- Node.js 22 (the version used by CI).
- For Android builds: Android SDK platform 35, build-tools 35.0.0, and Gradle 8.9.
- For Windows packaging from Linux: `bash`, `zip`, and `unzip`.

### Run the offline tests

```sh
sh tests/run-tests.sh
```

The suite uses synthetic fixtures and does not need the Android SDK or network access. It tests dictionary packaging and pins, the JavaScript data contract, Android activity signatures against local stubs, app logic, and the Windows archive.

### Build the APK

```sh
gradle --no-daemon :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. The build downloads and validates the pinned dictionary sources configured in [`dictionary.properties`](dictionary.properties).

### Package Windows

Build the APK/dictionary assets first, then run:

```sh
tools/package-windows-portable.sh \
  app/build/generated/nocturne/assets/dictionary \
  build/dist
```

The Windows archive is written to `build/dist/`. Generated dictionary assets and packages are build outputs; do not commit them.

## Project documentation

| Document | What it covers |
|---|---|
| [Contributing](CONTRIBUTING.md) | Development setup, tests, and pull-request expectations |
| [Security policy](SECURITY.md) | Private vulnerability reporting and scope |
| [Dictionary sources](DICTIONARY-SOURCES.md) | Upstream sources, licenses, attribution, and SHA-256 pins |
| [QA report](QA-REPORT.md) | Verified checks and remaining limitations |
| [Device validation checklist](DEVICE-VALIDATION-CHECKLIST.md) | Manual Android and Windows checks |
| [Public repository checklist](PUBLIC-REPOSITORY-CHECKLIST.md) | Repository settings and pre-release actions |
| [Windows portable edition](desktop/README.md) | Windows usage and troubleshooting |
| [Changelog](CHANGELOG.md) | Version history |

## Current limitations

- No full-sentence translation or contextual pronunciation model.
- The character pinyin fallback supplies one common reading per character and may be wrong in context.
- Segmentation frequencies choose word boundaries; they are not definitions.
- Audio handles are not persisted, so re-select audio after restarting a session.
- Audio pauses when the app leaves the foreground; background playback is not implemented.
- Physical-device Android and Windows runtime checks have not yet been completed.

## License status

**NOCTURNE does not yet include a project-level license.** Public visibility alone does not grant permission to reuse, redistribute, or create derivatives of the project's own code. The bundled dictionary and pinyin data retain their separate upstream terms; see [Dictionary sources](DICTIONARY-SOURCES.md). Do not describe this repository as an open-source release until a project license has been chosen and added.
