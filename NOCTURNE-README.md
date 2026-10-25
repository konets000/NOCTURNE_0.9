# NOCTURNE

**A quiet room for Chinese immersion. Your material, your pace, your device.**

NOCTURNE is a local-first Mandarin listening and reading tool for people who want to learn from the Chinese they actually watch and hear. Bring a Chinese subtitle file and its matching audio; follow the sentence, replay the difficult bit, tap a word, and keep the useful discoveries.

It is deliberately smaller in ambition than an all-purpose language platform—and more serious about the experience it does offer. No account to create. No learning streak demanding attention. No cloud service in the middle of your study session.

> **Current status: development preview · 0.8.1**  
> The automated source checks are encouraging, but physical Android and Windows runtime validation is still outstanding. CI packages are test builds, not stable releases.

## What NOCTURNE does today

- **Subtitle-led study.** Import `.srt` subtitles, preserve cue timestamps, filter for Chinese study material, and highlight the active cue during playback.
- **Listen closely.** Import matching local audio, seek, adjust playback speed, and loop a sentence.
- **Look up as you read.** Tap Chinese text to inspect segmentation, pinyin support, and offline dictionary entries.
- **Keep what matters.** Save words and subtitle sessions locally, then reopen a session later.
- **Use it on your own devices.** The Android edition wraps the shared reader in a WebView. The portable Windows edition opens the same reader in a dedicated Microsoft Edge window.
- **Study without a connection.** Once installed and its assets are present, the core reader and included dictionaries are designed for local use.

The current dictionary pipeline packages CC-CEDICT, Unicode Unihan data, and Jieba segmentation frequencies. These sources serve different purposes: definitions, character information, and segmentation hints are not interchangeable. The character pinyin fallback is only a best-effort aid; it does not resolve every pronunciation in context.

## What it is—and what it isn't

NOCTURNE is currently a **subtitle reader with offline lookup and local session storage**. It is not yet a complete course, an AI tutor, a full-sentence translation engine, or a spaced-repetition system. Those could become useful future layers, but they should not be confused with shipped functionality.

Current limitations worth knowing:

- There is no full-sentence translation or contextual pronunciation model.
- The character-pinyin fallback may choose the wrong reading for a particular word or sentence.
- Segmentation frequencies help select word boundaries; they are not dictionary definitions.
- Audio selection is not persisted with a saved session, so audio may need to be selected again after restarting.
- Playback pauses when the app leaves the foreground; background playback is not implemented.
- Device-specific behavior has not yet been fully validated on a physical Android device and a real Windows PC.

## Privacy by design

NOCTURNE aims to keep study material on the device:

- The Android manifest does not request the `INTERNET` permission.
- Subtitles, audio, sessions, and lookups are not designed to be uploaded to a NOCTURNE cloud service. There is no account or sync server.
- The Windows launcher binds its local server to `127.0.0.1:18473`, serves an explicit set of application assets, and is designed to stop when its managed Edge window closes.
- The Windows Edge profile is stored under `%LOCALAPPDATA%\NOCTURNE\BrowserProfile`.
- **Build-time network access is different from run-time privacy.** First-time builds download Gradle/Android dependencies and the pinned dictionary source files. The build validates configured hashes; an offline build requires the necessary dependencies and dictionary data to have already been cached or supplied locally.

These are design and source-level claims, not the result of an independent security audit. See [SECURITY.md](SECURITY.md), and complete the [device validation checklist](DEVICE-VALIDATION-CHECKLIST.md) before relying on a release.

## Get a development build

The GitHub Actions workflow is named **CI — test and build**. For pushes and manual workflow runs, it runs the checks, builds an Android debug APK, packages the Windows edition, and uploads artifacts for 14 days. Pull-request runs test and build but do not upload these artifacts.

1. Open the repository's **Actions** tab.
2. Open the latest successful **CI — test and build** run.
3. Download the `NOCTURNE-CI-…` artifact and extract the platform package you need.

### Known packaging mismatch in this source snapshot

The app source declares version **0.8.1**, but the current workflow still names its output files with **0.8.0**. The APK's internal app version and the artifact filename therefore disagree. Fix `.github/workflows/ci.yml` to derive the artifact names from the project version before distributing builds or treating the filenames as release labels.

### Android

Install the downloaded debug APK on your own device. Android may ask you to permit installation from the app used to open the downloaded file. These are temporary CI debug builds, signed with an ephemeral debug key; a later CI build may not install as an update. If Android reports a signature conflict, uninstall the earlier test build first. That removes its local app data, so export or back up anything important before doing so.

After installation, import a sample or personal `.srt` file and then select its matching audio file. The sample subtitles in [`samples/`](samples/) are provided for smoke testing.

### Windows

Extract the portable ZIP to a writable folder and run `start-nocturne.bat`. Keep the console window open while using the app. Microsoft Edge is required. No Python, Node.js, Java, Android Studio, or traditional installer is required to run the packaged edition. Close the managed app window to stop its local server.

## Build and test from source

### Prerequisites

- JDK 17 or newer.
- Node.js 22 for the test suite.
- For Android builds: Android SDK platform 35, build-tools 35.0.0, and Gradle 8.9.
- For Windows packaging from Linux: `bash`, `zip`, and `unzip`.

### Run the offline regression tests

```sh
sh tests/run-tests.sh
```

The test suite uses synthetic fixtures and does not need Android Studio or network access. It covers dictionary packaging and pin enforcement, the Java-to-JavaScript dictionary contract, app behavior, Android activity signatures against local stubs, and Windows archive smoke checks. Stub compilation is not the same as executing the app on Android; archive smoke tests are not a substitute for running the launcher on Windows.

### Build the Android debug APK

```sh
gradle --no-daemon :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The build generates dictionary assets using the pinned sources in [`dictionary.properties`](dictionary.properties). A first build generally needs internet access to download dependencies and source data. Review the source URLs, hashes, and attribution notes in [`DICTIONARY-SOURCES.md`](DICTIONARY-SOURCES.md).

### Package the Windows edition

Build the dictionary assets first, then run the packager through the shell:

```sh
sh tools/package-windows-portable.sh \
  app/build/generated/nocturne/assets/dictionary \
  build/dist
```

The resulting archive is written to `build/dist/`. Generated dictionary assets and packages are build outputs; do not commit them. Invoking the script through `sh` also avoids relying on executable permission bits being preserved when this project is moved in a ZIP archive.

## Architecture today

The project intentionally shares most of the learning experience across its two editions:

```text
Android shell (Java + WebView) ─┐
                                ├─ shared HTML / JavaScript reader
Windows launcher (PowerShell +  ┘          │
  local Edge window)                        ├─ subtitle and audio workflow
                                           ├─ dictionary lookup / segmentation
Gradle + Java dictionary packager ──────────└─ generated offline dictionary assets
```

In the current tree, the main reader is concentrated in `app/src/main/assets/app.js` and `index.html`. Android-specific responsibilities live in `MainActivity.java`; the Windows host and launcher live under `desktop/`; dictionary acquisition and packaging are handled by Gradle and `buildSrc/`.

This is a practical early architecture: one reader means fewer cross-platform behavior differences. The large front-end script is also a clear future maintenance pressure point. The answer should be **careful extraction behind tests**, not a speculative rewrite just because a framework is fashionable.

## A sensible path for future restructuring

The roadmap is intentionally a proposal, not a claim that these modules already exist.

### 1. Stabilize the current shape first

Before refactoring, close the release-readiness gaps: align artifact names with the app version, get successful CI artifacts, run the [Android and Windows device checklist](DEVICE-VALIDATION-CHECKLIST.md), choose a project-level license, and test backup/recovery behavior. Record which checks ran and on which platform.

### 2. Extract behavior into testable modules

As `app.js` grows, split it by responsibility rather than by screen or visual component. A possible destination is:

```text
reader/
  ui/                 # screen rendering and interactions
  core/
    subtitles/        # SRT parsing, filtering, cue validation
    chinese/          # segmentation and text normalization
    dictionary/       # lookup, readings, definitions, source metadata
    audio/            # playback state, synchronization, looping
    sessions/         # persistence, validation, migration, export/import
    review/            # future spaced-repetition logic
  platform/
    android/          # WebView and native file/lifecycle integration
    windows/          # local host and desktop integration
  tests/
```

The names and exact repository paths are open to change. The important boundary is that core rules should be testable without an Android device, a browser window, or a network connection. Platform shells should handle platform integration; they should not quietly duplicate subtitle, dictionary, or learning logic.

### 3. Make data formats stable before making features ambitious

Saved sessions and vocabulary are user data, even when stored locally. Introduce explicit schema versions, predictable migrations, validation for imports, and a documented export/restore format before adding more kinds of learning history. Add recovery tests for interrupted or malformed data. Optional encrypted backups can follow once the basic format is dependable.

### 4. Preserve the security boundaries during modularization

A future asset split must update the Android asset allowlist, Windows route allowlist, and Content Security Policy together. Do not solve modularity by exposing arbitrary filesystem paths, opening a network listener, adding broad permissions, or making remote services mandatory. Every new imported format should have size limits, strict parsing, and regression fixtures.

### 5. Add learning systems as separate layers

Once reading, storage, and cross-device export are stable, the strongest candidates are:

- **Spaced repetition** built on saved words and original subtitle context.
- **Phrase-level lookup** with clearer handling of ambiguous segmentation and context-dependent readings.
- **Study analytics** for words encountered, reviews due, and listening/reading time.
- **Unified content library** for subtitles, text, collections, and resume points.
- **Optional local intelligence** for sentence explanations or pronunciation support, only if it can stay private and run acceptably on target devices.

The guiding rule is simple: add a feature when it improves comprehension, recall, or continuity—not merely because it makes the settings screen longer.

## Definition of a dependable release

NOCTURNE should not call itself stable until all of the following are true:

- The offline regression suite passes from a clean checkout.
- CI builds the Android APK and Windows package with correctly versioned names.
- The APK is installed and exercised on a physical Android device.
- The portable package is run on a supported Windows PC, including launch, import, playback, shutdown, and port-conflict checks.
- Session persistence, migrations, and recovery are tested; users have a documented backup path.
- Dictionary sources, license terms, attribution, project license, release notes, and artifact checksums are documented.
- Official release signing is separate from CI debug signing; private signing keys are never committed, cached in source-controlled workflows, or uploaded as ordinary artifacts.

See [QA-REPORT.md](QA-REPORT.md) for the current verification status and [PUBLIC-REPOSITORY-CHECKLIST.md](PUBLIC-REPOSITORY-CHECKLIST.md) for publication checks.

## Project documents

| Document | Purpose |
|---|---|
| [Changelog](CHANGELOG.md) | Version history and notable changes |
| [QA report](QA-REPORT.md) | Verified checks and known verification gaps |
| [Device validation checklist](DEVICE-VALIDATION-CHECKLIST.md) | Manual Android and Windows validation |
| [Contributing](CONTRIBUTING.md) | Development workflow and contribution expectations |
| [Security policy](SECURITY.md) | Security issue reporting and scope |
| [Dictionary sources](DICTIONARY-SOURCES.md) | Upstream datasets, hashes, license and attribution notes |
| [Public repository checklist](PUBLIC-REPOSITORY-CHECKLIST.md) | Repository and release-readiness tasks |
| [Windows edition guide](desktop/README.md) | Portable Windows usage and troubleshooting |

## License status

**No project-level license has been selected yet.** Until one is added, do not assume that the project's own code may be reused, redistributed, or modified. Third-party dictionary and character data have separate terms and notices; see [`DICTIONARY-SOURCES.md`](DICTIONARY-SOURCES.md). Publicly visible source code is not automatically open source.

---

*NOCTURNE is being built around a simple promise: make it easier to stay with the Chinese sentence in front of you—and keep your learning yours.*
