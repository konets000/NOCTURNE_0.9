# Public repository checklist

For the repository owner. This covers GitHub settings and decisions that source files cannot set.

### Before changing repository visibility

- [ ] Review the full Git history for accidentally committed credentials, signing material, personal subtitles or audio, personal filesystem paths, and local configuration. The source ZIP does not include `.git` history, so this package-level review cannot clear it.
- [ ] Run a secret scanner over the full repository history. If a real credential was ever exposed, revoke or rotate it. Deleting the file is not enough.
- [ ] Inspect existing GitHub Actions artifacts. Delete any `NOCTURNE-private-signing-key-backup` artifact, and any other artifact that contains a keystore.
- [ ] Delete existing Actions caches created by the old `nocturne-personal-debug-keystore-...` cache key. The old workflow cached a debug keystore, and that cache must not survive into a public workflow.
- [ ] If the old debug keystore may have been accessible to an untrusted person, treat it as compromised. Replace it, and expect to uninstall the app before installing builds signed with the new key.
- [ ] Decide whether the goal is public source inspection or an open-source project. The repository currently has no project-level license. Add one only after considering the original project code and separately licensed bundled or derived data.
- [ ] Confirm that the LPPL obligations of the bundled xpinyin-derived pinyin data are understood for the intended distribution model.
- [ ] Check that the files staged for the first public commit exclude generated build outputs, personal data, local secrets, and signing keys.

### Suggested GitHub repository metadata

**Description**

> Offline-first Mandarin listening app for Android and Windows with synchronized SRT subtitles, local audio, pinyin, and offline dictionaries.

**Topics**

`mandarin` · `chinese-language-learning` · `android` · `windows` · `offline-first` · `subtitles` · `srt` · `pinyin` · `language-learning`

Add a screenshot only after capturing the real app on a device and checking that it contains no private subtitle text, filenames, or personal information. Do not use a mock interface as if it were a screenshot of the working app.

### Recommended GitHub settings

- [ ] Keep Actions workflow permissions read-only unless a future, reviewed release workflow clearly needs more.
- [ ] Enable Dependabot security and version updates. The repository includes a weekly `.github/dependabot.yml`.
- [ ] Enable private vulnerability reporting, if it is available for the repository.
- [ ] After the first successful public CI run, protect the default branch and require the CI check before merging pull requests.
- [ ] Decide who may create releases and tags. Do not publish CI debug APKs as official stable releases.
- [ ] Keep all release-signing keys outside the repository, Actions caches, and uploaded artifacts.
- [ ] If you add badges or download links, use the real repository URL once it is known. Do not commit guessed owner or repository placeholders.

### Before publishing a stable binary release

- [x] Set SHA-256 pins for CC-CEDICT, Unihan, and Jieba; Jieba points to an immutable revision.
- [ ] Run CI successfully on the exact release commit.
- [ ] Complete the physical Android and Windows validation in the [Device validation checklist](DEVICE-VALIDATION-CHECKLIST.md).
- [ ] Implement a separate release-signing process with controlled secret access and no key artifact uploads.
- [ ] Publish versioned release notes and checksums. Clearly identify supported platforms and known limitations.

---
