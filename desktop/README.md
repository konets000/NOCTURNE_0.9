# NOCTURNE Windows portable edition

This folder contains the Windows launcher source. The CI workflow (`CI — test and build`) packages the portable ZIP from the shared dictionary data.

### Run the packaged app

1. Download and extract `NOCTURNE-Windows-Portable-0.8.1.zip`.
2. Double-click `start-nocturne.bat`.
3. A dedicated Microsoft Edge app window opens. Keep the console window open while you use NOCTURNE. Close the app window to stop the local server.
4. Optionally, import `samples/bilingual-filter-demo.srt` to check that English and pinyin lines are ignored.
5. Choose **Import SRT**, then **Import audio**. Tap Chinese words for readings and dictionary entries.

### Notes

- The package includes the page and dictionaries. It does not install Python, Node.js, Java, Android Studio, or a background service. Microsoft Edge is required to provide the dedicated app window.
- The PowerShell launcher binds only to `127.0.0.1:18473`, serves a strict allowlist, and stops when its app window closes.
- Files stay on this computer. The app does not upload SRT files, audio, or lookup activity.
- The browser profile is stored at `%LOCALAPPDATA%\NOCTURNE\BrowserProfile`, so session and dictionary storage persist between launches.
- The server uses a fixed port to keep the browser origin stable. If that port is already in use, close the existing NOCTURNE window before starting another instance.

---
