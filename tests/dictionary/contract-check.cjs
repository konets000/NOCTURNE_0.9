'use strict';
// Cross-language contract check: confirms the app's JavaScript loaders accept exactly what the Java packager writes.
// Usage:  node tests/dictionary/contract-check.cjs <path to dictionary/ folder>
// Normally run through tests/run-tests.sh, which emits the folder with EmitFixture first.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const vm = require('vm');
const assert = require('assert/strict');

const dir = process.argv[2];
assert.ok(dir, 'pass the dictionary folder as the first argument');
const appPath = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'app.js');
const appScript = fs.readFileSync(appPath, 'utf8');

// Extract the same pure parser block the smoke test uses.
const dictStart = appScript.indexOf('  const CEDICT_DB_NAME=');
const stateStart = appScript.indexOf('  const state={', dictStart);
const parseStart = appScript.indexOf('  function decodeEntities');
const segmentStart = appScript.indexOf('  function isKnownWord', parseStart);
const code = [
  appScript.slice(dictStart, stateStart),
  appScript.slice(stateStart, parseStart),
  appScript.slice(parseStart, segmentStart),
  'globalThis.api = { parseCedictIntoMap, parseSegmentationRow, HAN, BROAD_CHAR_PINYIN, numericPinyinToMarks };',
].join('\n');
const context = { console, TextDecoder, Uint8Array, atob, Map, Set, String, Number, Array, Object, RegExp, Math, Date, setTimeout, clearTimeout };
vm.runInNewContext(code, context, { timeout: 30000 });
const api = context.api;

(async () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));

  // 1. CC-CEDICT: the app must accept the file and count the same entries the packager recorded.
  const cedictText = zlib.gunzipSync(fs.readFileSync(path.join(dir, 'cc-cedict.txt.gz'))).toString('utf8');
  const cedictMap = new Map();
  const meta = await api.parseCedictIntoMap(cedictText, cedictMap, () => {});
  assert.equal(meta.accepted, manifest.ccCedict.validSourceEntries, 'app and packager agree on the CC-CEDICT entry count');
  assert.ok(cedictMap.has('台湾'), 'the app indexes the packaged entries by word');
  console.log(`ok - CC-CEDICT: app accepted ${meta.accepted} entries, matching the manifest`);

  // 2. Segmentation: every packaged row must parse with the app's row parser, and totals must match the manifest.
  const segText = zlib.gunzipSync(fs.readFileSync(path.join(dir, 'segmentation.txt.gz'))).toString('utf8');
  let parsed = 0, total = 0;
  for (const line of segText.split('\n')) {
    if (!line) continue;
    const row = api.parseSegmentationRow(line);
    assert.ok(row, 'app rejected a packaged segmentation row: ' + JSON.stringify(line));
    parsed++;
    total += row.freq;
  }
  assert.equal(parsed, manifest.segmentation.bundledFrequencyTerms, 'row count matches the manifest');
  assert.equal(total, manifest.segmentation.sumOfIncludedFrequencies, 'frequency total matches the manifest');
  console.log(`ok - segmentation: ${parsed} rows parsed, frequency total ${total} matches the manifest`);

  // 3. Unihan: rows of [character, reading, definition], as restoreBundledUnihan() expects.
  const rows = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(dir, 'unihan-characters.json.gz'))).toString('utf8'));
  assert.equal(rows.length, manifest.unihan.characterRecords, 'Unihan row count matches the manifest');
  for (const r of rows) {
    assert.ok(Array.isArray(r) && r.length === 3, 'each Unihan row has three fields');
    assert.equal(typeof r[0], 'string');
    assert.equal(typeof r[1], 'string');
    assert.equal(typeof r[2], 'string');
  }
  const first = rows.find(r => r[0] === '一');
  assert.ok(first, 'the first fixture character is present');
  assert.equal(first[1], 'yī', 'reading is the Mandarin value');
  assert.equal(first[2], 'one; a, an', 'definition is the kDefinition value');
  console.log(`ok - Unihan: ${rows.length} rows, reading and definition fields correct`);

  // 4. Manifest keys the app reads must exist.
  assert.ok(manifest.ccCedict && typeof manifest.ccCedict.validSourceEntries === 'number', 'manifest.ccCedict.validSourceEntries');
  console.log('PASS: packager output satisfies the app loaders');
})().catch(err => { console.error('FAIL:', err.message); process.exitCode = 1; });
