# QA and verification status

NOCTURNE 0.8.1 is a development preview. This report distinguishes offline automated checks from full Android and Windows runtime validation.

## Verified in the current packaged source

The following checks passed when run from this exact source tree with OpenJDK 21 and Node.js 22.16.0:

- All 10 Java dictionary-packager self-tests, including pinned-download success, pin mismatch rejection, unpinned-download rejection, malformed UTF-8 rejection, CC-CEDICT ZIP handling, and output read-back verification.
- Java-to-JavaScript dictionary contract checks: app parser counts and packaged manifest metadata agree for CC-CEDICT, Unihan, and segmentation data.
- `MainActivity.java` compilation against the repository's Android/AndroidX stubs, with no compiler warnings. This checks signatures, not Android runtime behaviour.
- Node application smoke tests, including browser-storage recovery, dictionary/segmentation logic, Chinese-only SRT filtering, pinyin conversion, and overlapping subtitle cues.
- Portable Windows ZIP smoke test using synthetic dictionary fixtures.

Source-level checks also cover the local asset allowlist and the Windows loopback server's bounded HTTP parsing. These are code and smoke-test checks, not an independent security audit or device-runtime certification.

## Not yet verified

- A full Android Gradle build: Gradle and the Android SDK are not installed in this review environment. CI is configured to run `:app:assembleDebug`.
- Behaviour on a physical Android device, including file picking, edge-to-edge layout, WebView asset loading, and storage migration.
- Runtime behaviour on a Windows PC, including Edge launch/exit, port conflicts, file import and browser-profile persistence.
- Independent re-download and re-hash of all three full upstream dictionary datasets in this review. The package contains explicit pins and the tests cover pin enforcement, but this report does not claim a fresh network verification of those production files.

See [Device validation checklist](DEVICE-VALIDATION-CHECKLIST.md) before describing the app as device-verified or publishing stable binaries.
