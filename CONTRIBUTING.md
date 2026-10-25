# Contributing

Thanks for your interest. Contributions should keep NOCTURNE local-first and work across the Android and Windows editions where they apply.

### Before you open an issue or pull request

- Search existing issues for duplicates.
- Describe the behaviour you expected and what happened instead.
- Include device or OS details, the build source, and short reproduction steps.
- Use synthetic SRT samples. Do not attach private subtitles, media, session exports, credentials, or personally identifying files.
- For dictionary data changes, document the source, exact version or revision, license and terms, attribution, and a verified SHA-256 where applicable.

### Local checks

Install JDK 17+ and Node.js 22, then run:

```sh
sh tests/run-tests.sh
```

Changes to Android packaging should also be checked with the Android SDK and Gradle 8.9. Windows launcher or packaging changes should be tested on Windows before you describe them as verified.

### Pull request expectations

- Keep changes focused, and describe the user-visible effect.
- Add or update regression tests for behavioural changes.
- Do not add API keys, signing keys, private configuration, personal media, generated build output, or unlicensed dictionary data.
- Keep the Android network-permission boundary and the Windows loopback and route allowlist, unless a documented security review justifies a change.
- State which tests you ran and which platform checks remain unverified.

### License

The project has no project-level license yet. Until one is added, do not assume that your contribution carries any rights for downstream reuse or redistribution.

---
