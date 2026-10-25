# Dictionary sources

Download URLs and reviewed SHA-256 pins are recorded in `dictionary.properties`. The build verifies the exact bytes before packaging and fails if a network source is unpinned or has changed. The Jieba URL points to a specific upstream commit. Local files can replace downloads through the `-Pnocturne*File` Gradle properties; local overrides participate in Gradle's file inputs but intentionally skip upstream pin checks.

Before publishing stable binaries, keep these pins under review whenever an upstream dataset is updated. Local files can replace downloads through the `-Pnocturne*File` Gradle properties; local overrides skip the upstream pin check.

### CC-CEDICT: Chinese–English words

- Source: https://www.mdbg.net/chinese/dictionary?page=cc-cedict (export URL in `dictionary.properties`)
- Provides simplified and traditional headwords, numeric-tone pinyin, and slash-separated English senses.
- License: CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/
- Attribution: CC-CEDICT, maintained by MDBG and the CC-CEDICT editors, continuing the CEDICT project begun by Paul Andrew Denisowski.
- The build requires at least 100,000 valid entries and records the actual count in the manifest.
- The 0.8.0 source pin was computed from the MDBG export downloaded on 2026-10-24. The build validates the actual entry count and records it in the manifest.

### Unicode Unihan: character readings and definitions

- Source: https://www.unicode.org/Public/17.0.0/ucd/Unihan.zip (Unicode 17.0.0). Its expected SHA-256 is configured in `dictionary.properties`.
- Uses only two fields: `kMandarin` (from `Unihan_Readings.txt`) and `kDefinition` (from `Unihan_DictionaryLikeData.txt`). All other properties are ignored.
- Terms: https://www.unicode.org/license.html
- This is character-level data. A character's reading inside a compound can differ, so the app looks up whole words in CC-CEDICT first.

### Jieba: segmentation frequencies

- Source: https://raw.githubusercontent.com/fxsjy/jieba/67fa2e36e72f69d9134b8a1037b83fbb070b9775/extra_dict/dict.txt.big (immutable source revision recorded in `dictionary.properties`)
- Keeps Han-only terms of 1 to 12 code points with a positive frequency.
- Frequencies set word boundaries only. They are not English definitions and not a contextual model.
- License: MIT. The full upstream notice is included in the generated `dictionary/NOTICE.txt`. Source license: https://github.com/fxsjy/jieba/blob/master/LICENSE

### Character pinyin fallback (41,000+ characters)

- Embedded in `app/src/main/assets/app.js`. Derived from the xpinyin database, which states that it was generated from Unihan `kMandarin`.
- Source project: https://github.com/lxneng/xpinyin
- License stated by the source file: LPPL 1.3c or later, https://www.latex-project.org/lppl/lppl-1-3c.txt
- Gives one common reading per character. It cannot resolve context-dependent pronunciation.

### What the dictionaries do not cover

- Regional expressions, neologisms, names, and specialist vocabulary can be missing.
- Character definitions are not compound definitions.
- Word boundaries are conventional and can differ from other tools.
- Glosses can be ambiguous without context. There is no full-sentence translation.

---
