package com.nocturne.build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Self-test for DictionaryPackager. Runs without Gradle or network access, using synthetic fixtures.
 *
 * Run from the project root:
 *   java -m jdk.compiler/com.sun.tools.javac.Main -d build/selftest buildSrc/src/main/java/com/nocturne/build/DictionaryPackager.java tests/dictionary/PackagerSelfTest.java
 *   java -ea -cp build/selftest com.nocturne.build.PackagerSelfTest
 */
public final class PackagerSelfTest {

    private static int passed = 0;
    private static final List<String> log = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nocturne-packager-test");
        try {
            testCedictValidationRejectsTruncatedFile(root);
            testUnihanReadsOnlyMatchingProperties(root);
            testFullRunProducesVerifiableAssets(root);
            testMatchingPinAcceptsDownload(root);
            testPinMismatchDeletesCacheAndFails(root);
            testUnpinnedDownloadIsRejected(root);
            testCedictZipIsDetectedByMagicAndRepacked(root);
            testSegmentationVerifierCatchesLiteralBackslashT(root);
            testRejectsNonUtf8Dictionary(root);
            testJsonEscaping();
        } finally {
            deleteTree(root);
        }
        System.out.println("PASS: " + passed + " packager self-tests");
    }

    // ---------------------------------------------------------------- fixtures

    static Path cedictFixture(Path dir, int validEntries, String name) throws IOException {
        Path p = dir.resolve(name);
        StringBuilder sb = new StringBuilder();
        sb.append("# CC-CEDICT\n# Synthetic fixture for packager self-test\n");
        for (int i = 0; i < validEntries; i++) {
            sb.append("字").append(i).append(" 字").append(i).append(" [zi4] /character ").append(i).append("/\n");
        }
        sb.append("this line is not an entry\n");
        sb.append("台湾 台灣 [Tai2 wan1] /Taiwan/\n");
        Files.write(p, gzip(sb.toString()));
        return p;
    }

    static Path unihanFixture(Path dir) throws IOException {
        Path p = dir.resolve("Unihan.zip");
        String readings =
                "U+4E00\tkCantonese\tjat1\n" +
                "U+4E00\tkMandarin\tyī\n" +
                "U+4E00\tkVietnamese\tnhất\n" +
                "U+4E8C\tkMandarin\tèr\n" +
                "U+4E8C\tkTang\tɦi\n";
        String dictLike =
                "U+4E00\tkDefinition\tone; a, an\n" +
                "U+4E00\tkTotalStrokes\t1\n" +
                "U+4E8C\tkDefinition\ttwo; a \"pair\"\n" +
                "U+4E8C\tkFrequency\t3\n";
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(p))) {
            zos.putNextEntry(new ZipEntry("Unihan/Unihan_Readings.txt"));
            zos.write(readings.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("Unihan/Unihan_DictionaryLikeData.txt"));
            zos.write(dictLike.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return p;
    }

    static Path jiebaFixture(Path dir) throws IOException {
        Path p = dir.resolve("dict.txt.big");
        String text =
                "中国 1000 ns\n" +
                "中国人 500 n\n" +
                "a 5 x\n" +              // not Han: rejected
                "我们 0 r\n" +           // zero frequency: rejected
                "新 abc\n" +             // non-numeric: rejected
                "一二三四五六七八九十一二三 7 m\n" + // 13 code points: rejected
                "的 2000 uj\n";
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    static DictionaryPackager.Options baseOptions(Path root, String tag) {
        DictionaryPackager.Options o = new DictionaryPackager.Options();
        o.cacheDir = root.resolve(tag).resolve("cache");
        o.outputDir = root.resolve(tag).resolve("out");
        o.minCedictEntries = 50;
        o.minUnihanRecords = 2;
        o.minJiebaLines = 3;
        o.minSegmentationTerms = 3;
        o.minDownloadBytes = 1;
        o.log = log::add;
        return o;
    }

    // ---------------------------------------------------------------- tests

    static void testCedictValidationRejectsTruncatedFile(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("cedict-small"));
        DictionaryPackager.Options o = baseOptions(root, "cedict-small");
        o.cedictOverride = cedictFixture(dir, 5, "small.txt.gz");
        o.unihanOverride = unihanFixture(dir);
        o.jiebaOverride = jiebaFixture(dir);
        try {
            DictionaryPackager.run(o);
            fail("expected a truncated CC-CEDICT file to be rejected");
        } catch (IOException expected) {
            check(expected.getMessage().contains("validation failed"), "message names the validation failure");
        }
        pass("CC-CEDICT with too few valid entries is rejected");
    }

    static void testUnihanReadsOnlyMatchingProperties(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("unihan"));
        DictionaryPackager.Source src = new DictionaryPackager.Source("Unihan", unihanFixture(dir), false, null);
        java.util.TreeMap<Integer, String[]> recs = DictionaryPackager.readUnihan(src, 2);
        String[] one = recs.get(0x4E00);
        String[] two = recs.get(0x4E8C);
        check(one != null && two != null, "both characters parsed");
        check("yī".equals(one[0]), "pinyin is kMandarin, not kVietnamese (got " + one[0] + ")");
        check("one; a, an".equals(one[1]), "meaning is kDefinition, not kTotalStrokes (got " + one[1] + ")");
        check("èr".equals(two[0]), "second character pinyin is kMandarin (got " + two[0] + ")");
        check("two; a \"pair\"".equals(two[1]), "quotes in definition preserved");
        pass("Unihan reader filters by property name (regression for overwrite bug)");
    }

    static void testFullRunProducesVerifiableAssets(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("full"));
        DictionaryPackager.Options o = baseOptions(root, "full");
        o.cedictOverride = cedictFixture(dir, 60, "cedict.txt.gz");
        o.unihanOverride = unihanFixture(dir);
        o.jiebaOverride = jiebaFixture(dir);
        DictionaryPackager.Result r = DictionaryPackager.run(o);

        check(r.cedictEntries == 61, "CC-CEDICT counts 60 synthetic + 1 Taiwan entry (got " + r.cedictEntries + ")");
        check(r.unihanRecords == 2, "two Unihan records");
        check(r.segmentationTerms == 3, "three usable Jieba rows (got " + r.segmentationTerms + ")");
        check(r.segmentationTotalFrequency == 3500L, "frequency total 1000+500+2000 (got " + r.segmentationTotalFrequency + ")");

        Path dict = o.outputDir.resolve("dictionary");
        for (String f : new String[]{"cc-cedict.txt.gz", "unihan-characters.json.gz", "segmentation.txt.gz",
                "manifest.json", "NOTICE.txt"}) {
            check(Files.isRegularFile(dict.resolve(f)), "asset present: " + f);
        }

        // Segmentation: real tabs, two fields per row, no literal backslash-t.
        String seg = gunzipToString(dict.resolve("segmentation.txt.gz"));
        check(seg.contains("中国\t1000\n"), "segmentation uses real tab and newline separators");
        check(!seg.contains("\\t"), "no literal backslash-t in segmentation asset");
        check(seg.contains("的\t2000\n"), "single-character Han rows are kept");

        // Unihan JSON: rows of [char, pinyin, meaning], quotes escaped.
        String uh = gunzipToString(dict.resolve("unihan-characters.json.gz"));
        check(uh.startsWith("[[\"一\",\"yī\",\"one; a, an\"]"), "Unihan JSON row shape (got " + uh.substring(0, Math.min(60, uh.length())) + ")");
        check(uh.contains("two; a \\\"pair\\\""), "Unihan JSON escapes embedded quotes");

        // Manifest: the fields the app reads, and hashes of every output.
        String manifest = Files.readString(dict.resolve("manifest.json"), StandardCharsets.UTF_8);
        check(manifest.contains("\"validSourceEntries\": 61"), "manifest records CC-CEDICT count the app reads");
        check(manifest.contains("\"sha256Asset\": \""), "manifest records asset hashes");
        check(manifest.contains("\"appVersion\": \"0.8.1\""), "manifest carries app version");
        String notice = Files.readString(dict.resolve("NOTICE.txt"), StandardCharsets.UTF_8);
        check(notice.contains("Packaged source: local file: Unihan.zip"), "NOTICE records the input actually used");
        check(!notice.contains("Public/UCD/latest/Unihan.zip"), "NOTICE does not retain the obsolete Unihan path");
        check(notice.contains("Copyright (c) 2013 Sun Junyi")
                && notice.contains("THE SOFTWARE IS PROVIDED \"AS IS\""), "NOTICE includes the verbatim Jieba MIT notice");
        check(r.sha256.containsKey("segmentation.txt.gz"), "result exposes asset hashes");
        pass("full run writes all five assets and a verifiable manifest");
    }

    static void testPinMismatchDeletesCacheAndFails(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("pin-src"));
        Path cedict = cedictFixture(dir, 60, "cedict.txt.gz");
        DictionaryPackager.Options o = baseOptions(root, "pin-bad");
        o.cedictUrl = cedict.toUri().toString();
        o.cedictSha256 = "0".repeat(64);
        o.unihanOverride = unihanFixture(dir);
        o.jiebaOverride = jiebaFixture(dir);
        try {
            DictionaryPackager.run(o);
            fail("expected a pin mismatch to abort the run");
        } catch (IOException expected) {
            check(expected.getMessage().contains("SHA-256 mismatch"), "error reports the hash mismatch");
        }
        check(!Files.exists(o.cacheDir.resolve("cc-cedict.download")), "mismatched cache copy was deleted");
        pass("pinned download with the wrong SHA-256 is rejected and the cache is cleared");
    }

    static void testMatchingPinAcceptsDownload(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("pin-good-src"));
        Path cedict = cedictFixture(dir, 60, "cedict.txt.gz");
        DictionaryPackager.Options o = baseOptions(root, "pin-good");
        o.cedictUrl = cedict.toUri().toString();
        o.cedictSha256 = DictionaryPackager.sha256(cedict);
        o.unihanOverride = unihanFixture(dir);
        o.jiebaOverride = jiebaFixture(dir);
        log.clear();
        DictionaryPackager.Result result = DictionaryPackager.run(o);
        check(result.cedictEntries == 61, "matching pinned download is packaged");
        check(log.stream().anyMatch(m -> m.contains("CC-CEDICT: sha256 matches pin")), "matching pin is reported");
        pass("pinned network source succeeds when its SHA-256 matches");
    }

    static void testUnpinnedDownloadIsRejected(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("unpinned-src"));
        Path cedict = cedictFixture(dir, 60, "cedict.txt.gz");
        DictionaryPackager.Options o = baseOptions(root, "unpinned");
        o.cedictUrl = cedict.toUri().toString();
        o.cedictSha256 = "";
        o.unihanOverride = unihanFixture(dir);
        o.jiebaOverride = jiebaFixture(dir);
        try {
            DictionaryPackager.run(o);
            fail("expected an unpinned download to be rejected");
        } catch (IOException expected) {
            check(expected.getMessage().contains("no SHA-256 pin configured"), "error explains that a reviewed pin is required");
        }
        check(!Files.exists(o.cacheDir.resolve("cc-cedict.download")), "unpinned cache copy was deleted");
        pass("unpinned network download is rejected and its cache copy is cleared");
    }

    static void testCedictZipIsDetectedByMagicAndRepacked(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("cedict-zip"));
        Path plainText = dir.resolve("cedict_data.txt");
        StringBuilder sb = new StringBuilder("# CC-CEDICT\n");
        for (int i = 0; i < 60; i++) {
            sb.append("字").append(i).append(" 字").append(i).append(" [zi4] /character ").append(i).append("/\n");
        }
        sb.append("台湾 台灣 [Tai2 wan1] /Taiwan/\n");
        Files.writeString(plainText, sb.toString(), StandardCharsets.UTF_8);
        DictionaryPackager.Source plainSource = new DictionaryPackager.Source("CC-CEDICT", plainText, false, null);
        check(DictionaryPackager.validateCedict(plainSource, 50, new StringBuilder()) == 61,
                "plain UTF-8 text is parsed independent of filename suffix");

        // Deliberately use a .gz suffix for a ZIP archive: the reader must identify the actual content.
        Path zipSource = dir.resolve("cc-cedict.txt.gz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipSource))) {
            zip.putNextEntry(new ZipEntry("export/cedict_1_0_ts_utf-8_mdbg.txt"));
            zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        DictionaryPackager.Source source = new DictionaryPackager.Source("CC-CEDICT", zipSource, false, null);
        StringBuilder header = new StringBuilder();
        check(DictionaryPackager.validateCedict(source, 50, header) == 61, "ZIP member is parsed as CC-CEDICT text");
        Path output = dir.resolve("normalized.gz");
        DictionaryPackager.writeGzipAsset(zipSource, output);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(output)), StandardCharsets.UTF_8))) {
            check("# CC-CEDICT".equals(r.readLine()), "ZIP source is repacked as gzip text, not nested ZIP bytes");
            check(r.readLine() != null, "repacked CC-CEDICT content is present");
        }
        pass("CC-CEDICT ZIP is detected from magic bytes and normalized into the gzip asset");
    }

    static void testSegmentationVerifierCatchesLiteralBackslashT(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("verify"));
        Path bad = dir.resolve("bad-seg.txt.gz");
        Files.write(bad, gzip("中国\\t1000\n中国人\\t500\n"));   // the literal escape bug from 0.5.x
        try {
            DictionaryPackager.verifySegmentation(bad, 2, 1500);
            fail("literal backslash-t must fail verification");
        } catch (IOException expected) {
            check(expected.getMessage().contains("not tab-separated"), "verifier names the row problem");
        }
        pass("segmentation read-back rejects the literal backslash-t escape");
    }

    static void testRejectsNonUtf8Dictionary(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("bad-encoding"));
        // A valid CC-CEDICT header, then bytes that cannot occur in UTF-8 (0xFF and 0xFE never appear in it).
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write("# CC-CEDICT\n".getBytes(StandardCharsets.UTF_8));
        raw.write(new byte[] {(byte) 0xFF, (byte) 0xFE, '\n'});
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(gz)) {
            out.write(raw.toByteArray());
        }
        Path bad = dir.resolve("cedict.txt.gz");
        Files.write(bad, gz.toByteArray());
        DictionaryPackager.Source source = new DictionaryPackager.Source("CC-CEDICT", bad, false, null);
        try {
            DictionaryPackager.validateCedict(source, 1, new StringBuilder());
            fail("malformed UTF-8 must not be silently replaced");
        } catch (IOException expected) {
            check(expected.getMessage().contains("could not be read as CC-CEDICT text"),
                    "error names the unreadable dictionary (got " + expected.getMessage() + ")");
        }
        pass("non-UTF-8 dictionary text is rejected instead of being silently repaired");
    }

    static void testJsonEscaping() {
        check("\"a\\\"b\\\\c\\n\"".equals(DictionaryPackager.jsonString("a\"b\\c\n")), "JSON escapes quote, backslash, newline");
        check("\"\\u0001\"".equals(DictionaryPackager.jsonString("\u0001")), "JSON escapes control characters");
        pass("JSON string escaping");
    }

    // ---------------------------------------------------------------- helpers

    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bos)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    static String gunzipToString(Path gz) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            char[] buf = new char[1 << 14];
            int n;
            while ((n = r.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }

    static void fail(String message) {
        throw new AssertionError("FAILED: " + message);
    }

    static void pass(String name) {
        passed++;
        System.out.println("  ok - " + name);
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                try {
                    Files.deleteIfExists(q);
                } catch (IOException ignored) {
                    // Temporary directory cleanup is best effort.
                }
            });
        }
    }
}
