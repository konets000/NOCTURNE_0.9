'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const ROOT = path.join(__dirname, '..');
const appPath = path.join(ROOT, 'app', 'src', 'main', 'assets', 'index.html');
const appScriptPath = path.join(ROOT, 'app', 'src', 'main', 'assets', 'app.js');
const html = fs.readFileSync(appPath, 'utf8');
const scriptTags = [...html.matchAll(/<script\b[^>]*>/gi)].map(m => m[0]);
assert.deepEqual(scriptTags, ['<script src="app.js" defer>'], 'HTML loads the application from its same-origin script asset');
const appScript = fs.readFileSync(appScriptPath, 'utf8');
new vm.Script(appScript, { filename: 'nocturne-inline.js' });

// Extract the pure dictionary, subtitle-parser, segmenter, and lookup logic without a DOM.
const dictStart = appScript.indexOf('  const CEDICT_DB_NAME=');
const stateStart = appScript.indexOf('  const state={', dictStart);
assert.ok(dictStart >= 0 && stateStart > dictStart, 'dictionary block found');
const dictCode = appScript.slice(dictStart, stateStart);
const parseStart = appScript.indexOf('  function decodeEntities');
const segmentStart = appScript.indexOf('  function isKnownWord', parseStart);
const sessionStart = appScript.indexOf('  function getSession()', segmentStart);
assert.ok(parseStart >= 0 && segmentStart > parseStart && sessionStart > segmentStart, 'parser and segmentation blocks found');
const stateToParserStart = appScript.slice(stateStart, parseStart);
const parserCode = appScript.slice(parseStart, segmentStart);
const segmentCode = appScript.slice(segmentStart, sessionStart);
const getInfoStart = appScript.indexOf('  function getCharacterInfo(ch)');
const createElStart = appScript.indexOf('  function createEl(', getInfoStart);
assert.ok(getInfoStart >= 0 && createElStart > getInfoStart, 'dictionary fallback lookup found');
const getInfoCode = appScript.slice(getInfoStart, createElStart);

const testCode = `const STORAGE={sessions:'nocturne.sessions.v1',active:'nocturne.active.v1',saved:'nocturne.saved.v1',speed:'nocturne.speed.v1',loop:'nocturne.loop.v1'};\n${dictCode}\n${stateToParserStart}\n${parserCode}\n${segmentCode}\n${getInfoCode}\n` + String.raw`
  globalThis.nocturneTestApi = {
    DICT, CHAR, CEDICT, MULTI, BROAD_CHAR_PINYIN, UNIHAN_CHARACTERS, SEGMENTATION_FREQUENCY, getCharacterInfo, segment, segmentHanziRun, findWordAt, parseSrt,
    parseCedictIntoMap, parseSegmentationRow, numericPinyinToMarks, getInfo, missingWordHelp,
    normalizeStoredSessions, normalizeSavedWords, storageNeedsRepair, loadStore, state, parseTime,
    decompressGzip, extractCedictZip, CEDICT_MAX_BYTES, MAX_CUES_PER_SESSION, MAX_CUE_TEXT_CHARS, installCedictText,
    get maxDictionaryWordLength() { return maxDictionaryWordLength; },
    set maxDictionaryWordLength(value) { maxDictionaryWordLength = value; },
    setInstalled(entries) { CEDICT.clear(); for (const [k,v] of entries) CEDICT.set(k,v); },
    setFrequency(entries,total) { SEGMENTATION_FREQUENCY.clear(); for (const [k,v] of entries) { SEGMENTATION_FREQUENCY.set(k,v); } SEGMENTATION_TOTAL_FREQUENCY=total; maxDictionaryWordLength=Math.max(1,...Array.from(entries, pair=>Array.from(pair[0]).length)); },
  };
`;
const storage = new Map();
const ui = new Map();
const context = {
  console, setTimeout, clearTimeout, TextDecoder, Uint8Array, DataView, Blob, Response, DecompressionStream, atob, Map, Set, String, Number, Array, Object, RegExp, Math, Date,
  window: { DecompressionStream, localStorage: { getItem: key => storage.has(key) ? storage.get(key) : null, setItem: (key, value) => storage.set(key, String(value)) } },
  $: id => { if (!ui.has(id)) ui.set(id, {textContent:'',setAttribute(){},classList:{toggle(){}}}); return ui.get(id); },
  getSession: () => null, renderSession: () => {}, renderWordTab: () => {},
};
vm.runInNewContext(testCode, context, { timeout: 30000, filename: 'nocturne-pure-logic.vm.js' });
const api = context.nocturneTestApi;

// Persisted browser data is untrusted input. Invalid values are repaired without blocking startup.
api.loadStore();
assert.equal(api.state.storageRecovered, false, 'a clean first run does not report storage damage');
storage.set('nocturne.sessions.v1', '{');
assert.doesNotThrow(() => api.loadStore(), 'invalid JSON in browser storage does not crash startup');
assert.equal(api.state.storageRecovered, true);
assert.equal(storage.get('nocturne.sessions.v1'), '[]', 'invalid JSON is replaced with a safe default');
storage.set('nocturne.sessions.v1', JSON.stringify({unexpected:'object'}));
storage.set('nocturne.active.v1', 'missing-session');
storage.set('nocturne.saved.v1', JSON.stringify({unexpected:'object'}));
storage.set('nocturne.speed.v1', '12');
storage.set('nocturne.loop.v1', 'sometimes');
assert.doesNotThrow(() => api.loadStore(), 'malformed persisted shapes do not crash startup');
assert.equal(api.state.sessions.length, 0);
assert.equal(api.state.activeId, null);
assert.equal(api.state.saved.size, 0);
assert.equal(api.state.speed, 1);
assert.equal(api.state.loop, false);
assert.equal(api.state.storageRecovered, true);
assert.equal(storage.get('nocturne.sessions.v1'), '[]', 'malformed session storage is repaired');
assert.equal(storage.get('nocturne.saved.v1'), '[]', 'malformed saved-word storage is repaired');
storage.set('nocturne.sessions.v1', JSON.stringify([{id:'s123456',name:'demo.srt',title:'demo',cues:[{start:0,end:1,text:'中文'},{start:'bad',end:2,text:'invalid'}],lastTime:3,createdAt:4}]));
storage.set('nocturne.active.v1', 's123456');
storage.set('nocturne.saved.v1', JSON.stringify(['中文', 42, '中文']));
storage.set('nocturne.speed.v1', '1.25');
storage.set('nocturne.loop.v1', 'true');
api.loadStore();
assert.equal(api.state.sessions.length, 1, 'valid saved sessions survive recovery');
assert.equal(api.state.sessions[0].cues.length, 1, 'invalid cue records are filtered');
assert.equal(api.state.activeId, 's123456');
assert.deepEqual([...api.state.saved], ['中文']);
assert.equal(api.state.speed, 1.25);
assert.equal(api.state.loop, true);
assert.equal(api.state.storageRecovered, true, 'invalid cue and saved-word entries trigger a one-time repair notice');
storage.set('nocturne.sessions.v1', JSON.stringify([{id:'s654321',name:'out-of-order.srt',title:'out of order',cues:[{start:4,end:5,text:'后面'},{start:1,end:2,text:'前面'}],lastTime:0,createdAt:0}]));
storage.set('nocturne.active.v1', 's654321');
api.loadStore();
assert.deepEqual(JSON.parse(JSON.stringify(api.state.sessions[0].cues.map(c => c.start))), [1,4], 'recovered cues are sorted for binary-search playback synchronization');
assert.equal(JSON.parse(storage.get('nocturne.sessions.v1'))[0].cues[0].start, 1, 'out-of-order stored sessions are repaired persistently');
storage.set('nocturne.sessions.v1', JSON.stringify([{id:'s987654',name:'oversized.srt',title:'oversized',cues:[{start:0,end:1,text:'中'.repeat(api.MAX_CUE_TEXT_CHARS + 1)}],lastTime:0,createdAt:0}]));
storage.set('nocturne.active.v1', 's987654');
api.loadStore();
assert.equal(api.state.sessions.length, 0, 'oversized stored cue text is discarded before it can freeze transcript rendering');
storage.set('nocturne.sessions.v1', JSON.stringify([{id:'s234567',name:'instant.srt',title:'instant',cues:[{start:1,end:1,text:'瞬间'}],lastTime:0,createdAt:0}]));
storage.set('nocturne.active.v1', 's234567');
api.loadStore();
assert.equal(api.state.sessions.length, 0, 'zero-duration stored cues are discarded because loop playback cannot progress through them');

(async () => {
  // A manual selection must win even if startup has already queued automatic restoration.
  const autoDictionary = Array(1000).fill('自动 自动 [zi4 dong4] /automatic/').join('\n');
  const manualDictionary = Array(1000).fill('手动 手动 [shou3 dong4] /manual/').join('\n');
  const queuedAutomaticRestore = api.installCedictText(autoDictionary, 'bundled.txt', false);
  const manualInstall = api.installCedictText(manualDictionary, 'manual.txt', true);
  await Promise.all([queuedAutomaticRestore, manualInstall]);
  assert.equal(api.CEDICT.get('手动').meaning, 'manual', 'manual dictionary import wins over a queued automatic restoration');
  assert.equal(api.CEDICT.has('自动'), false, 'skipped automatic restoration cannot overwrite the manual import');
  api.setInstalled([]);

  // Reported gaps: starter pack must recognize both simplified/traditional variants.
  const reported = {
    '台湾': ['Táiwān', 'Taiwan; the island and polity of Taiwan'],
    '台灣': ['Táiwān', 'Taiwan; traditional variant spelling'],
    '臺灣': ['Táiwān', 'Taiwan; traditional spelling'],
    '监狱': ['jiānyù', 'prison; jail'],
    '監獄': ['jiānyù', 'prison; jail; traditional spelling'],
    '打': ['dǎ', 'to hit; strike; beat; make a phone call; play a sport or game; type; perform an action'],
  };
  for (const [word, expected] of Object.entries(reported)) {
    assert.ok(api.DICT.has(word), 'starter word record: ' + word);
    assert.equal(api.DICT.get(word).pinyin, expected[0], 'pinyin: ' + word);
    assert.equal(api.DICT.get(word).meaning, expected[1], 'meaning: ' + word);
  }
  for (const [ch, pinyin] of Object.entries({
    '湾':'wān', '灣':'wān', '监':'jiān', '監':'jiān', '狱':'yù', '獄':'yù',
    '台':'tái', '臺':'tái', '打':'dǎ', '象':'xiàng', '免':'miǎn',
    '费':'fèi', '費':'fèi', '卫':'wèi', '衛':'wèi', '裤':'kù', '褲':'kù'
  })) {
    assert.equal(api.CHAR.get(ch)?.pinyin, pinyin, 'character pinyin: ' + ch);
    assert.ok(api.CHAR.get(ch)?.meaning, 'character meaning must not be empty: ' + ch);
    assert.ok(api.getInfo(ch).pinyin, 'single-character lookup pinyin: ' + ch);
    assert.ok(api.getInfo(ch).meaning, 'single-character lookup meaning: ' + ch);
  }
  assert.deepEqual(JSON.parse(JSON.stringify(api.segment('台湾监狱打。').filter(x => x.word).map(x => x.text))), ['台湾', '监狱', '打']);
  assert.deepEqual(JSON.parse(JSON.stringify(api.segment('免费卫生裤子大象避免。').filter(x => x.word).map(x => x.text))), ['免费','卫生','裤子','大象','避免']);
  for (const [word,py] of Object.entries({'免费':'miǎnfèi','免費':'miǎnfèi','费用':'fèiyòng','費用':'fèiyòng','卫生':'wèishēng','衛生':'wèishēng','裤子':'kùzi','褲子':'kùzi','大象':'dàxiàng','避免':'bìmiǎn'})) {
    assert.equal(api.getInfo(word).pinyin, py, 'compound pinyin: ' + word);
    assert.ok(api.getInfo(word).meaning, 'compound definition: ' + word);
  }
  assert.equal(api.getInfo('台湾').pinyin, 'Táiwān');
  assert.deepEqual(JSON.parse(JSON.stringify(api.parseSegmentationRow('監獄\t8312'))), {word:'監獄',freq:8312});
  assert.equal(api.parseSegmentationRow('監獄\\t8312'), null, 'literal backslash-t must not be treated as a real frequency separator');
  assert.equal(api.parseSegmentationRow('監獄\tNaN'), null, 'invalid frequencies are rejected');
  // Regression for globally frequency-aware boundaries, not greedy longest-match.
  api.setFrequency([['甲乙丙丁', 1], ['甲乙', 500000], ['丙丁', 500000], ['甲', 1], ['乙', 1], ['丙', 1], ['丁', 1]], 1000005);
  assert.deepEqual(JSON.parse(JSON.stringify(api.segmentHanziRun('甲乙丙丁'))), ['甲乙', '丙丁']);
  // Restore the starter-only test environment before the remaining assertions.
  api.setFrequency([], 0);
  assert.equal(api.getInfo('not-known').source, 'missing');
  assert.ok(api.BROAD_CHAR_PINYIN.size >= 41000, 'broad character pinyin pack covers more than 41,000 characters');
  assert.equal(api.getInfo('㐀').pinyin, 'qiū', 'rare CJK extension character receives broad fallback pinyin');
  assert.equal(api.getInfo('㐀').source, 'broad-character-reading');
  api.UNIHAN_CHARACTERS.set('㐀', {word:'㐀', pinyin:'qiū', meaning:'hillock', source:'Unihan'});
  assert.equal(api.getInfo('㐀').meaning, 'hillock', 'local Unihan data supersedes pinyin-only fallback');
  const unknownCompound = api.getInfo('㐀㐁');
  assert.equal(unknownCompound.source, 'character-fallback', 'unknown compounds receive explicit character-level clues');
  assert.ok(unknownCompound.pinyin.includes(' '), 'compound fallback keeps readings separated rather than claiming word-level pronunciation');
  api.SEGMENTATION_FREQUENCY.set('㐀㐁㐄', 1);
  api.maxDictionaryWordLength = Math.max(api.maxDictionaryWordLength, 3);
  assert.equal(api.findWordAt('㐀㐁㐄', 0), '㐀㐁㐄', 'supplemental segmentation dictionary supplies non-definition word boundaries');
  assert.equal(api.getInfo('㐀㐁㐄').source, 'segmentation-only', 'segmentation-only term is not presented as a bilingual dictionary entry');
  assert.match(api.missingWordHelp(), /import CC-CEDICT/i);

  // Chinese-only filter: Hanzi retained, separate and inline romanization removed, timestamps untouched.
  const raw = '1\n00:00:01,000 --> 00:00:02,000\n台湾监狱打。\nTaiwan prison hit.\ntái wān jiān yù dǎ\n\n2\n00:00:02,000 --> 00:00:03,000\n監獄里没有人。';
  const parsed = api.parseSrt(raw);
  assert.equal(parsed.cues.length, 2);
  assert.equal(parsed.cues[0].text, '台湾监狱打。');
  assert.equal(parsed.cues[0].start, 1);
  assert.equal(parsed.cues[0].end, 2);
  assert.equal(parsed.cues[1].text, '監獄里没有人。');
  assert.throws(() => api.parseSrt('1\n00:00:01,000 --> 00:00:02,000\nHello world.\nni hao'), /No Hanzi-containing/);
  assert.equal(api.parseTime('12:59:59,999'), 46799.999, 'valid SRT clocks accept legal minute and second values');
  assert.ok(Number.isNaN(api.parseTime('00:60:00,000')), 'invalid SRT minute values are rejected');
  assert.ok(Number.isNaN(api.parseTime('00:00:60,000')), 'invalid SRT second values are rejected');
  const invalidClock = api.parseSrt('1\n00:99:00,000 --> 00:99:01,000\n坏时间\n\n2\n00:00:01,000 --> 00:00:02,000\n好时间');
  assert.equal(invalidClock.cues.length, 1, 'invalid timestamp blocks cannot shift playback timing');
  assert.equal(invalidClock.cues[0].text, '好时间');
  const zeroDuration = api.parseSrt('1\n00:00:01,000 --> 00:00:01,000\n瞬间\n\n2\n00:00:02,000 --> 00:00:03,000\n正常');
  assert.equal(zeroDuration.cues.length, 1, 'zero-duration SRT cues are rejected so loop playback always advances');
  assert.equal(zeroDuration.cues[0].text, '正常');
  assert.throws(() => api.parseSrt(`1\n00:00:01,000 --> 00:00:02,000\n${'中'.repeat(api.MAX_CUE_TEXT_CHARS + 1)}`), /character safety limit/, 'oversized cue text is rejected before DOM rendering');
  const excessiveCues = Array(api.MAX_CUES_PER_SESSION + 1).fill('00:00:01,000 --> 00:00:02,000\n中').join('\n\n');
  assert.throws(() => api.parseSrt(excessiveCues), /cannot be rendered safely/, 'caption imports have a bounded cue count');
  const compressed = await new Response(new Blob(['CC-CEDICT test text']).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
  assert.equal(await api.decompressGzip(compressed), 'CC-CEDICT test text', 'bounded gzip reader preserves valid UTF-8 dictionary input');
  await assert.rejects(api.extractCedictZip(new Uint8Array([0x50,0x4b,0x05,0x06]).buffer), /Could not find|invalid entry offsets/, 'truncated ZIP input is rejected safely');
  assert.equal(api.numericPinyinToMarks('zhong1 guo2 ni3 hao3 lü4 lu:4 liu2 shui3 xue2 nv3 yi1'), 'zhōng guó nǐ hǎo lǜ lǜ liú shuǐ xué nǚ yī');

  // Parse genuine CC-CEDICT line structure, preserve capitalized proper-name pinyin,
  // keep traditional/simplified lookup keys, and separate senses with different readings.
  const syntheticEntries = [
    '臺灣 台湾 [Tai2 wan1] /Taiwan (island and polity)/',
    '監獄 监狱 [jian1 yu4] /prison/jail/',
    '打 打 [da3] /to hit/to strike/',
    '張 张 [Zhang1] /surname Zhang/',
    '張 张 [zhang1] /to open up/to spread/',
  ];
  // The parser requires 1,000 valid entries to reject truncated/wrong files.
  const syntheticCedict = '# CC-CEDICT synthetic parser fixture\n' + Array.from({length: 200}, () => syntheticEntries.join('\n')).join('\n');
  const imported = new Map();
  const meta = await api.parseCedictIntoMap(syntheticCedict, imported, () => {});
  assert.equal(meta.accepted, 1000);
  for (const k of ['台湾', '臺灣', '监狱', '監獄', '打', '张', '張']) assert.ok(imported.has(k), 'imported searchable form: ' + k);
  assert.match(imported.get('台湾').pinyin, /Táiwān/);
  assert.match(imported.get('监狱').meaning, /prison/);
  assert.equal(imported.get('打').pinyin, 'dǎ');
  assert.ok(imported.get('张').senses.some(s => s.pinyin === 'Zhāng')); // Proper-name case is not lowercased away.
  assert.ok(imported.get('张').senses.some(s => s.pinyin === 'zhāng'));

  const read = rel => fs.readFileSync(path.join(ROOT, ...rel.split('/')), 'utf8');

  // ---- Windows edition and public CI artifact builder -----------------------------------
  const launcher = read('desktop/start-nocturne.ps1');
  assert.ok(launcher.includes('IPAddress]::Loopback') && launcher.includes('$Port = 18473'), 'Windows web server binds to a stable loopback origin');
  assert.ok(launcher.includes('Read-BoundedHttpLine') && launcher.includes('$headerChars -gt 16384'), 'Windows server bounds request-line and aggregate-header memory use');
  for (const route of ['/app.js','/dictionary/cc-cedict.txt','/dictionary/unihan-characters.json','/dictionary/segmentation.txt','/dictionary/manifest.json','/dictionary/NOTICE.txt']) {
    assert.ok(launcher.includes(`'${route}'`), 'Windows server serves dictionary route ' + route);
  }
  assert.ok(launcher.includes('default {') && launcher.includes('404'), 'Windows server uses an allowlist and has a not-found response');
  assert.ok(launcher.includes("script-src 'self'") && !launcher.includes("script-src 'self' 'unsafe-inline'"), 'Windows CSP disallows inline scripts');
  assert.ok(launcher.includes('switch -Exact ($requestPath)') && !launcher.includes('Join-Path $Root $requestPath'), 'request paths are matched against an allowlist rather than used as file paths');
  assert.ok(launcher.includes('Microsoft Edge is required') && launcher.includes('exit 4') && !launcher.includes('Start-Process $BaseUrl'), 'portable launcher exits predictably when its managed Edge app window cannot be started');
  assert.ok(read('desktop/start-nocturne.bat').includes('start-nocturne.ps1'), 'Windows launcher is double-clickable');
  assert.ok(read('tools/package-windows-portable.sh').includes('cc-cedict.txt.gz'), 'Windows package requires real generated dictionary data');
  assert.ok(read('tools/package-windows-portable.sh').includes('app.js'), 'Windows package includes the external application script');
  assert.ok(read('tools/package-windows-portable.sh').includes('cp -R "$ROOT/samples"'), 'Windows package includes importable sample subtitles');
  const workflow = read('.github/workflows/ci.yml');
  assert.ok(workflow.includes(':app:assembleDebug'), 'workflow builds an Android debug APK');
  assert.ok(workflow.includes('package-windows-portable.sh'), 'workflow packages the portable Windows edition');
  assert.ok(workflow.includes('push:') && workflow.includes('pull_request:') && workflow.includes('workflow_dispatch:'), 'public CI runs for pushes, pull requests, and manual runs');
  assert.ok(workflow.includes('permissions:') && workflow.includes('contents: read'), 'CI token permissions are read-only');
  assert.ok(!workflow.includes('actions/cache') && !workflow.includes('NOCTURNE_DEBUG_KEYSTORE_BASE64') && !workflow.includes('signing-key-backup'), 'workflow must not cache, restore, or upload a signing key');
  assert.ok(/uses: actions\/checkout@[a-f0-9]{40}/.test(workflow), 'checkout action is pinned to a full commit SHA');
  assert.ok(/uses: actions\/setup-java@[a-f0-9]{40}/.test(workflow), 'setup-java action is pinned to a full commit SHA');
  assert.ok(/uses: actions\/setup-node@[a-f0-9]{40}/.test(workflow), 'setup-node action is pinned to a full commit SHA');
  assert.ok(/uses: actions\/upload-artifact@[a-f0-9]{40}/.test(workflow), 'upload-artifact action is pinned to a full commit SHA');
  // ---- Version and structure ------------------------------------------------------------
  assert.ok(appScript.includes("version:'0.8.1'"), 'HTML version metadata must be 0.8.1');
  const appBuild = read('app/build.gradle');
  assert.ok(appBuild.includes("def nocturneVersionName = '0.8.1'"), 'Gradle version must match HTML');
  assert.ok(appBuild.includes('versionCode 13'), 'versionCode bumped with the release');
  assert.ok(appBuild.includes("tasks.register('prepareNocturneDictionaryAssets', PrepareDictionaryAssetsTask)"), 'dictionary task registered');
  assert.ok(!appBuild.includes('verifyNocturneReleaseDictionaryPins'), 'Google Play/release pin gate is not part of the current debug build');
  assert.ok(appBuild.includes('compileSdk 35') && appBuild.includes('targetSdk 35'), 'debug build targets Android API 35 without Play-only requirements');
  assert.ok(read('build.gradle').includes("version '8.7.3'"), 'AGP 8.7 supports API 35');
  assert.ok(fs.existsSync(path.join(ROOT, '.github/workflows/ci.yml')), 'public CI workflow is included');
  const usesLines = [...workflow.matchAll(/^\s*uses:\s+[^@\s]+@([^\s]+)/gm)];
  assert.ok(usesLines.length >= 6 && usesLines.every(m => /^[a-f0-9]{40}$/.test(m[1])), 'all third-party actions are pinned to full commit SHAs');
  assert.ok(fs.existsSync(path.join(ROOT, '.github/dependabot.yml')), 'Dependabot is configured for Actions and Gradle dependencies');
  assert.ok(read('gradle/wrapper/gradle-wrapper.properties').includes('gradle-8.9-bin.zip'), 'wrapper Gradle matches AGP 8.7');
  assert.ok(appBuild.includes("assets.srcDir(prepareDictionaries.flatMap { it.outputDir })"), 'generated assets are wired through flatMap (map would nest the provider)');
  assert.ok(appBuild.includes("task.dependsOn(prepareDictionaries)"), 'every merge*Assets task depends on the dictionary task');
  assert.ok(!fs.existsSync(path.join(ROOT, 'android')), 'no stale android/ folder: the layout is the standard one');

  // ---- Manifest: no network permission, standard layout ----------------------------------
  const manifest = read('app/src/main/AndroidManifest.xml');
  assert.ok(!/uses-permission[^>]*INTERNET/.test(manifest), 'manifest must not request INTERNET');
  assert.ok(manifest.includes('usesCleartextTraffic="false"'), 'cleartext traffic stays disabled');

  // ---- Pinned data sources ---------------------------------------------------------------
  const pins = read('dictionary.properties');
  for (const key of ['cedict.url', 'cedict.sha256', 'unihan.url', 'unihan.sha256', 'jieba.url', 'jieba.sha256']) {
    assert.ok(new RegExp('^' + key.replace('.', '\\.') + '=', 'm').test(pins), 'dictionary.properties defines ' + key);
  }
  for (const key of ['cedict.sha256', 'unihan.sha256', 'jieba.sha256']) {
    const match = pins.match(new RegExp('^' + key.replace('.', '\\.') + '=([a-f0-9]{64})$', 'm'));
    assert.ok(match, key + ' is a complete lower-case SHA-256 pin');
  }
  assert.match(pins, /jieba\.url=https:\/\/raw\.githubusercontent\.com\/fxsjy\/jieba\/[a-f0-9]{40}\//, 'Jieba source uses an immutable commit URL');

  // ---- Activity: no JS bridge, assets served by WebViewAssetLoader ------------------------
  const activity = read('app/src/main/java/com/nocturne/app/MainActivity.java');
  assert.ok(!activity.includes('addJavascriptInterface'), 'the JavaScript bridge must not be exposed');
  assert.ok(!activity.includes('setAllowFileAccess(true)'), 'file access must stay disabled');
  assert.ok(activity.includes('WebViewAssetLoader') && activity.includes('"/dictionary/"'), 'dictionaries served under /dictionary/');
  assert.ok(activity.includes('new AppAssetHandler(this)') && activity.includes('!"index.html".equals(path) && !"app.js".equals(path)'), 'only the app entry point and script are exposed via /assets/');
  assert.ok(activity.includes('"cc-cedict.txt"') && activity.includes('"segmentation.txt"') && activity.includes('"unihan-characters.json"'), 'allowlist covers every bundled dataset');
  assert.ok(activity.includes('ViewCompat.setOnApplyWindowInsetsListener(webView') && activity.includes('ViewCompat.requestApplyInsets(webView)'), 'WebView applies system-bar insets immediately (targetSdk 35 is edge-to-edge); device behaviour is in the checklist');
  assert.ok(activity.includes('WindowCompat.setDecorFitsSystemWindows(getWindow(), false)'), 'edge-to-edge behavior is consistent across Android API levels');
  assert.ok(!activity.includes('Set.of('), 'avoid Java library APIs newer than minSdk 24 without core-library desugaring');
  assert.ok(activity.includes("document.getElementById('audio');if(a)a.pause()"), 'audio pauses when the app leaves foreground');
  assert.ok(activity.includes('request.hasGesture()'), 'external links open only from a user gesture');

  // ---- Packager: the logic behind the build, checked for the defects found in 0.5.x -------
  const packager = read('buildSrc/src/main/java/com/nocturne/build/DictionaryPackager.java');
  assert.ok(packager.includes('!f[1].equals(property)'), 'Unihan reader filters by property name (0.5.x overwrote readings)');
  assert.ok(packager.includes('verifySegmentation(segAsset'), 'segmentation is read back after compression');
  assert.ok(packager.includes('"\\t"') || packager.includes("'\\t'"), 'segmentation writes real tab separators');
  assert.ok(packager.includes('\\p{IsHan}'), 'Han filter uses the Unicode script class');
  assert.ok(packager.includes('SHA-256 mismatch'), 'pinned downloads are hash-checked');
  assert.ok(packager.includes('no SHA-256 pin configured'), 'unpinned network sources fail the build');
  assert.ok(packager.includes('CodingErrorAction.REPORT'), 'dictionary text is decoded strictly, not silently repaired');
  const dictionaryTask = read('buildSrc/src/main/groovy/com/nocturne/build/PrepareDictionaryAssetsTask.groovy');
  assert.ok(dictionaryTask.includes('@InputFile') && dictionaryTask.includes('PathSensitivity.NAME_ONLY'), 'local dictionary override names and contents participate in Gradle task inputs');

  // ---- Bundled loading path ---------------------------------------------------------------
  assert.ok(appScript.includes("fetchBundled('cc-cedict.txt')"), 'CC-CEDICT corpus loads from /dictionary/');
  assert.ok(appScript.includes("fetchBundled('unihan-characters.json')"), 'Unihan loads from /dictionary/');
  assert.ok(appScript.includes("fetchBundled('segmentation.txt')"), 'segmentation loads from /dictionary/');
  assert.ok(appScript.includes("fetchBundled('manifest.json')"), 'manifest loads from /dictionary/');
  assert.ok(appScript.includes("'/dictionary/'+name"), 'bundled fetches use the same-origin absolute path');
  assert.ok(!appScript.includes('NocturneNative') && !appScript.includes('getBundled') && !appScript.includes('decompressBase64Gzip'), 'base64 bridge code is gone');
  assert.ok(appScript.includes('function decompressBounded') && appScript.includes('total>CEDICT_MAX_BYTES') && appScript.includes('reader.cancel()'), 'dictionary decompression is streaming and capped before expansion exhausts memory');
  assert.ok(appScript.includes('function requireZipRange') && appScript.includes('Encrypted ZIP files are not supported'), 'ZIP imports reject malformed offsets and encrypted entries');
  assert.ok(appScript.includes('MAX_CUES_PER_SESSION=20000') && appScript.includes('MAX_CUE_TEXT_CHARS=16384'), 'subtitle imports bound cue count and cue text before transcript rendering');
  assert.ok(appScript.includes('manualCedictImportRequested') && appScript.includes('manualCedictImportInstalled') && appScript.includes('cedictInstallQueue') && appScript.includes('userImported:true'), 'manual dictionary imports are serialized and win over concurrent or later bundled-dictionary restoration');
  assert.ok(appScript.includes('!storedPack?.userImported'), 'an explicitly imported dictionary remains preferred after the next app launch');

  // ---- Playback: overlapping cues ---------------------------------------------------------
  assert.ok(appScript.includes('let pick=-1;'), 'overlapping cues: the latest still-active cue is highlighted');
  assert.ok(appScript.includes('selectCue(pick,{scroll:true,persist:false})'), 'automatic playback highlighting does not rewrite saved sessions on each cue change');

  assert.ok(!appScript.includes('No entry in the installed offline dictionaries yet.'), 'remove old generic missing-entry error');
  assert.ok(!appScript.includes('No character entry found in installed data.'), 'never show legacy missing-character diagnostic');
  console.log('PASS: storage recovery, dictionary and segmentation behaviour, Chinese-only SRT filtering, timestamps, tone marks, CC-CEDICT import, multiple readings, overlapping-cue highlighting, and v0.8.1 structure, manifest, pins, asset access, and bundle loading.');
})().catch(err => { console.error(err); process.exitCode = 1; });

// ---- Behaviour: overlapping cues and gaps. Runs the real syncCueToAudio() against a stub DOM. -------
(() => {
  const start = appScript.indexOf('  function syncCueToAudio(){');
  assert.ok(start >= 0, 'syncCueToAudio found');
  const end = appScript.indexOf('\n  function ', start + 10);
  const fnSource = appScript.slice(start, end);
  const selected = [];
  let autoCuePersist;
  const manyOverlaps = [
    { start: 0, end: 10 },
    { start: 1, end: 2 }, { start: 1.5, end: 2.5 }, { start: 2, end: 3 },
    { start: 2.5, end: 3.5 }, { start: 3, end: 3.75 }, { start: 3.5, end: 4 }
  ];
  const sandbox = {
    state: { audioUrl: 'blob:test', cues: manyOverlaps, selectedCue: 6 },
    cuePrefixMaxEnd: [10, 10, 10, 10, 10, 10, 10],
    $: id => id === 'audio' ? ({ currentTime: 5 }) : ({ textContent: '' }),
    document: { querySelectorAll: () => [] },
    selectCue: (i, options) => { selected.push(i); autoCuePersist = options && options.persist; },
  };
  vm.runInNewContext(fnSource + '\nsyncCueToAudio();', sandbox, { timeout: 2000 });
  assert.deepEqual(selected, [0], 'a long cue remains active even when more than three later cues have ended');
  assert.equal(autoCuePersist, false, 'automatic playback cue changes do not serialize session storage');

  // Gaps and positions before the first cue must clear the previous highlight, not leave it stale.
  let cleared = 0;
  const quiet = {
    state: { audioUrl: 'blob:test', cues: [{ start: 0, end: 1 }, { start: 2, end: 3 }], selectedCue: 1 },
    cuePrefixMaxEnd: [1, 3],
    $: id => id === 'audio' ? ({ currentTime: 20 }) : ({ textContent: '' }),
    document: { querySelectorAll: () => [{ classList: { remove: () => { cleared++; } }, removeAttribute: () => {} }] },
    selectCue: () => assert.fail('no cue should be selected during a gap'),
  };
  vm.runInNewContext(fnSource + '\nsyncCueToAudio();', quiet, { timeout: 2000 });
  assert.equal(quiet.state.selectedCue, -1, 'after all cues end, selectedCue is cleared');
  assert.equal(cleared, 1, 'the old visual highlight is removed');
  console.log('PASS: overlapping-cue highlighting and gap clearing behave correctly in the real function');
})();
