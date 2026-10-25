package com.nocturne.build;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Build-time packager for NOCTURNE's offline dictionary assets.
 *
 * <p>This class has no Gradle or Android dependency, so it can be exercised directly by
 * {@code tests/dictionary/PackagerSelfTest.java}. The Gradle task in buildSrc is a thin wrapper.
 *
 * <p>Output layout under {@code outputDir/dictionary/}:
 * <ul>
 *   <li>{@code cc-cedict.txt.gz}: validated CC-CEDICT export (CC BY-SA 4.0)</li>
 *   <li>{@code unihan-characters.json.gz}: rows of [character, Mandarin reading(s), definition]</li>
 *   <li>{@code segmentation.txt.gz}: tab-separated word and frequency rows for the segmenter</li>
 *   <li>{@code manifest.json}: counts, source URLs, and SHA-256 of every input and output</li>
 *   <li>{@code NOTICE.txt}: attribution text</li>
 * </ul>
 */
public final class DictionaryPackager {

    static final String DICTIONARY_DIR = "dictionary";

    /** One CC-CEDICT entry line: traditional simplified [pin1 yin1] /sense/sense/ */
    static final Pattern CEDICT_ENTRY =
            Pattern.compile("^\\S+\\s+\\S+\\s+\\[[^\\]]+\\]\\s+/.*/\\s*$");
    static final Pattern HAN_ONLY = Pattern.compile("\\p{IsHan}+");

    /** Every input source is either a pinned download or a local override. */
    public static final class Options {
        public Path cacheDir;
        public Path outputDir;
        public String appVersion = "0.8.1";

        public String cedictUrl;
        public String unihanUrl;
        public String jiebaUrl;

        /** Expected SHA-256 (lower-case hex) of each download. Blank pins fail closed for network sources. */
        public String cedictSha256;
        public String unihanSha256;
        public String jiebaSha256;

        /** Local files that replace the download entirely (no pin check is applied to these). */
        public Path cedictOverride;
        public Path unihanOverride;
        public Path jiebaOverride;

        public int minCedictEntries = 100_000;
        public int minUnihanRecords = 20_000;
        public int minJiebaLines = 100_000;
        public int minSegmentationTerms = 100_000;
        public long minDownloadBytes = 100_000L;
        public int connectTimeoutMs = 30_000;
        public int readTimeoutMs = 180_000;

        public Consumer<String> log = message -> { };
    }

    public static final class Result {
        public final int cedictEntries;
        public final int unihanRecords;
        public final long segmentationTerms;
        public final long segmentationTotalFrequency;
        public final Map<String, String> sha256 = new LinkedHashMap<>();

        Result(int cedictEntries, int unihanRecords, long segmentationTerms, long totalFrequency) {
            this.cedictEntries = cedictEntries;
            this.unihanRecords = unihanRecords;
            this.segmentationTerms = segmentationTerms;
            this.segmentationTotalFrequency = totalFrequency;
        }
    }

    static final class Source {
        final String name;
        final Path path;
        final boolean fromUrl;
        final String url;

        Source(String name, Path path, boolean fromUrl, String url) {
            this.name = name;
            this.path = path;
            this.fromUrl = fromUrl;
            this.url = url;
        }
    }

    private DictionaryPackager() { }

    public static Result run(Options o) throws IOException {
        Objects.requireNonNull(o.cacheDir, "cacheDir");
        Objects.requireNonNull(o.outputDir, "outputDir");
        Files.createDirectories(o.cacheDir);
        Path dictDir = o.outputDir.resolve(DICTIONARY_DIR);
        Files.createDirectories(dictDir);

        // 1. CC-CEDICT: acquire, pin-check, validate.
        Source cedict = acquire(o, "CC-CEDICT", o.cedictOverride, o.cedictUrl, o.cacheDir.resolve("cc-cedict.download"));
        String cedictSha = pinCheck(o, cedict, o.cedictSha256);
        StringBuilder cedictHeader = new StringBuilder();
        int cedictEntries = validateCedict(cedict, o.minCedictEntries, cedictHeader);
        o.log.accept("CC-CEDICT: " + cedictEntries + " valid entries (" + Files.size(cedict.path) + " bytes)");

        // 2. Unihan: acquire, pin-check, read only kMandarin and kDefinition.
        Source unihan = acquire(o, "Unihan", o.unihanOverride, o.unihanUrl, o.cacheDir.resolve("Unihan.zip"));
        String unihanSha = pinCheck(o, unihan, o.unihanSha256);
        TreeMap<Integer, String[]> unihanRecords = readUnihan(unihan, o.minUnihanRecords);
        o.log.accept("Unihan: " + unihanRecords.size() + " character records");

        // 3. Jieba: acquire, pin-check, filter to Han words of 1-12 code points with positive frequency.
        Source jieba = acquire(o, "Jieba", o.jiebaOverride, o.jiebaUrl, o.cacheDir.resolve("dict.txt.big"));
        String jiebaSha = pinCheck(o, jieba, o.jiebaSha256);

        // 4. Write assets.
        Path cedictAsset = dictDir.resolve("cc-cedict.txt.gz");
        writeGzipAsset(cedict.path, cedictAsset);

        Path unihanAsset = dictDir.resolve("unihan-characters.json.gz");
        writeUnihanJson(unihanRecords, unihanAsset);

        Path segAsset = dictDir.resolve("segmentation.txt.gz");
        SegStats seg = writeSegmentation(jieba, segAsset, o.minJiebaLines, o.minSegmentationTerms);
        verifySegmentation(segAsset, seg.terms, seg.totalFrequency);
        o.log.accept("Segmentation: " + seg.terms + " terms, frequency total " + seg.totalFrequency + " (read-back verified)");

        Files.writeString(dictDir.resolve("NOTICE.txt"), buildNotice(cedict, unihan, jieba), StandardCharsets.UTF_8);

        // 5. Manifest, written last so it can record the hash of every asset.
        Result result = new Result(cedictEntries, unihanRecords.size(), seg.terms, seg.totalFrequency);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("appVersion", o.appVersion);
        manifest.put("createdAtUtc", Instant.now().toString());

        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("sourceUrl", describe(cedict));
        cc.put("header", cedictHeader.toString());
        cc.put("validSourceEntries", cedictEntries);
        cc.put("sha256Source", cedictSha);
        cc.put("sha256Asset", sha256(cedictAsset));
        cc.put("license", "CC BY-SA 4.0");
        manifest.put("ccCedict", cc);

        Map<String, Object> uh = new LinkedHashMap<>();
        uh.put("sourceUrl", describe(unihan));
        uh.put("characterRecords", unihanRecords.size());
        uh.put("sha256Source", unihanSha);
        uh.put("sha256Asset", sha256(unihanAsset));
        uh.put("license", "Unicode data license (https://www.unicode.org/license.html)");
        manifest.put("unihan", uh);

        Map<String, Object> sg = new LinkedHashMap<>();
        sg.put("sourceUrl", describe(jieba));
        sg.put("sourceLines", seg.sourceLines);
        sg.put("bundledFrequencyTerms", seg.terms);
        sg.put("sumOfIncludedFrequencies", seg.totalFrequency);
        sg.put("maxCodePointLength", 12);
        sg.put("sha256Source", jiebaSha);
        sg.put("sha256Asset", sha256(segAsset));
        sg.put("license", "MIT (Jieba project)");
        manifest.put("segmentation", sg);

        manifest.put("note", "Data is bundled in the APK at build time. The installed app has no internet permission.");

        StringBuilder json = new StringBuilder();
        writeJson(manifest, json, 0);
        json.append('\n');
        Files.writeString(dictDir.resolve("manifest.json"), json.toString(), StandardCharsets.UTF_8);

        for (Path p : new Path[]{cedictAsset, unihanAsset, segAsset, dictDir.resolve("manifest.json")}) {
            result.sha256.put(dictDir.relativize(p).toString(), sha256(p));
        }
        return result;
    }

    // ------------------------------------------------------------------ acquisition

    private static Source acquire(Options o, String name, Path override, String url, Path cached) throws IOException {
        if (override != null) {
            if (!Files.isRegularFile(override)) {
                throw new IOException(name + ": local override not found: " + override);
            }
            o.log.accept(name + ": using local file " + override);
            return new Source(name, override, false, null);
        }
        if (url == null || url.isBlank()) {
            throw new IOException(name + ": no download URL configured");
        }
        Source s = new Source(name, cached, true, url);
        boolean needDownload = true;
        if (Files.isRegularFile(cached)) {
            needDownload = false;
            o.log.accept(name + ": using cached download " + cached);
        }
        if (needDownload) {
            download(o, name, url, cached);
        }
        return s;
    }

    /**
     * Enforces a reviewed SHA-256 for every downloaded source. Local overrides are intentionally
     * exempt because their provenance is supplied by the developer. Missing or incorrect pins fail
     * closed and discard the cached copy so it cannot accidentally be packaged by a later run.
     */
    private static String pinCheck(Options o, Source s, String expected) throws IOException {
        String actual = sha256(s.path);
        if (!s.fromUrl) {
            return actual;
        }
        if (expected == null || expected.isBlank()) {
            Files.deleteIfExists(s.path);
            throw new IOException(s.name + ": no SHA-256 pin configured. Review the downloaded source and set its "
                    + "lower-case SHA-256 in dictionary.properties before building.");
        }
        if (!actual.equalsIgnoreCase(expected.trim())) {
            Files.deleteIfExists(s.path);
            throw new IOException(s.name + ": SHA-256 mismatch. Expected " + expected.trim() + " but got " + actual
                    + ". The cached copy was deleted. If the upstream file was deliberately updated, update the pin.");
        }
        o.log.accept(s.name + ": sha256 matches pin");
        return actual;
    }

    static void download(Options o, String name, String url, Path dest) throws IOException {
        Files.createDirectories(dest.toAbsolutePath().getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        o.log.accept(name + ": downloading " + url);
        URLConnection connection = java.net.URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(o.connectTimeoutMs);
        connection.setReadTimeout(o.readTimeoutMs);
        connection.setRequestProperty("User-Agent", "NOCTURNE offline dictionary packager");
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection;
            http.setInstanceFollowRedirects(true);
            int code = http.getResponseCode();
            if (code / 100 != 2) {
                http.disconnect();
                throw new IOException(name + ": HTTP " + code + " from " + url);
            }
        }
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        long size = Files.size(tmp);
        if (size < o.minDownloadBytes) {
            Files.deleteIfExists(tmp);
            throw new IOException(name + ": download was only " + size + " bytes; refusing to package it");
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ CC-CEDICT

    /**
     * Opens CC-CEDICT text by inspecting file content rather than trusting a filename or URL suffix.
     * Supports plain UTF-8 text, gzip-compressed text, and ZIP exports containing a .txt member.
     */
    static BufferedReader openText(Path p) throws IOException {
        byte[] header = new byte[4];
        int count;
        try (InputStream probe = Files.newInputStream(p)) {
            count = probe.read(header);
        }

        if (count >= 2 && (header[0] & 0xff) == 0x1f && (header[1] & 0xff) == 0x8b) {
            return utf8Reader(new GZIPInputStream(Files.newInputStream(p), 1 << 16));
        }
        if (isZipHeader(header, count)) {
            ZipFile zip = new ZipFile(p.toFile());
            ZipEntry entry = findCedictTextEntry(zip);
            if (entry == null) {
                zip.close();
                throw new IOException("CC-CEDICT ZIP contains no .txt entry");
            }
            try {
                InputStream entryStream = zip.getInputStream(entry);
                InputStream managed = new FilterInputStream(entryStream) {
                    @Override
                    public void close() throws IOException {
                        IOException failure = null;
                        try {
                            super.close();
                        } catch (IOException ex) {
                            failure = ex;
                        }
                        try {
                            zip.close();
                        } catch (IOException ex) {
                            if (failure == null) failure = ex;
                            else failure.addSuppressed(ex);
                        }
                        if (failure != null) throw failure;
                    }
                };
                return utf8Reader(managed);
            } catch (IOException ex) {
                try { zip.close(); } catch (IOException closeEx) { ex.addSuppressed(closeEx); }
                throw ex;
            }
        }
        return utf8Reader(Files.newInputStream(p));
    }

    private static boolean isZipHeader(byte[] header, int count) {
        if (count < 4 || header[0] != 'P' || header[1] != 'K') return false;
        int third = header[2] & 0xff;
        int fourth = header[3] & 0xff;
        return (third == 0x03 && fourth == 0x04)
                || (third == 0x05 && fourth == 0x06)
                || (third == 0x07 && fourth == 0x08);
    }

    private static ZipEntry findCedictTextEntry(ZipFile zip) {
        ZipEntry fallback = null;
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.endsWith(".txt")) continue;
            if (name.contains("cedict")) return entry;
            if (fallback == null) fallback = entry;
        }
        return fallback;
    }

    /**
     * Strict UTF-8 reader. The default decoder silently replaces malformed bytes with U+FFFD and carries on, so a
     * corrupt dictionary would be packaged with wrong characters and no error. This one fails the build instead.
     */
    static BufferedReader utf8Reader(InputStream in) {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new BufferedReader(new InputStreamReader(in, strict), 1 << 16);
    }

    static int validateCedict(Source s, int minEntries, StringBuilder header) throws IOException {
        int valid = 0;
        try {
            try (BufferedReader r = openText(s.path)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#")) {
                        if (header.length() == 0 && line.startsWith("# CC-CEDICT")) {
                            header.append(line);
                        }
                        continue;
                    }
                    if (CEDICT_ENTRY.matcher(line).matches()) {
                        valid++;
                    }
                }
            }
        } catch (IOException ex) {
            onInvalidDownload(s);
            throw new IOException(s.name + ": could not be read as CC-CEDICT text: " + ex.getMessage(), ex);
        }
        if (valid < minEntries) {
            onInvalidDownload(s);
            throw new IOException(s.name + ": validation failed with " + valid + " valid entries; at least "
                    + minEntries + " are required");
        }
        return valid;
    }

    /** Writes normalized UTF-8 source text into a gzip asset, regardless of the source container format. */
    static void writeGzipAsset(Path src, Path dest) throws IOException {
        try (BufferedReader reader = openText(src);
             Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dest), 1 << 16),
                     StandardCharsets.UTF_8)) {
            char[] buffer = new char[1 << 16];
            int read;
            while ((read = reader.read(buffer)) != -1) writer.write(buffer, 0, read);
        }
    }

    // ------------------------------------------------------------------ Unihan

    /**
     * Reads kMandarin from Unihan_Readings.txt and kDefinition from Unihan_DictionaryLikeData.txt.
     * Each line is filtered by property name. An earlier version did not filter, so the last property in the file
     * (for example kVietnamese or kTotalStrokes) overwrote the reading or definition.
     */
    static TreeMap<Integer, String[]> readUnihan(Source s, int minRecords) throws IOException {
        TreeMap<Integer, String[]> recs = new TreeMap<>();
        try (ZipFile zf = new ZipFile(s.path.toFile())) {
            readUnihanProperty(zf, "Unihan_Readings.txt", "kMandarin", recs);
            readUnihanProperty(zf, "Unihan_DictionaryLikeData.txt", "kDefinition", recs);
        } catch (IOException ex) {
            onInvalidDownload(s);
            throw new IOException(s.name + ": not a readable Unihan ZIP: " + ex.getMessage(), ex);
        }
        if (recs.size() < minRecords) {
            onInvalidDownload(s);
            throw new IOException(s.name + ": only " + recs.size() + " character records; at least "
                    + minRecords + " are required");
        }
        return recs;
    }

    private static void readUnihanProperty(ZipFile zf, String fileName, String property,
                                           TreeMap<Integer, String[]> recs) throws IOException {
        ZipEntry entry = null;
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (n.equals(fileName) || n.endsWith("/" + fileName)) {
                entry = e;
                break;
            }
        }
        if (entry == null) {
            throw new IOException("Unihan archive has no " + fileName);
        }
        try (BufferedReader r = utf8Reader(zf.getInputStream(entry))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", 3);
                if (f.length != 3 || !f[0].startsWith("U+") || !f[1].equals(property)) {
                    continue;
                }
                int cp;
                try {
                    cp = Integer.parseInt(f[0].substring(2), 16);
                } catch (NumberFormatException ex) {
                    continue;
                }
                String value = f[2].trim();
                if (value.isEmpty()) {
                    continue;
                }
                String[] rec = recs.computeIfAbsent(cp, k -> new String[]{"", ""});
                if (property.equals("kDefinition")) {
                    rec[1] = value;
                } else {
                    rec[0] = value;
                }
            }
        }
    }

    static void writeUnihanJson(TreeMap<Integer, String[]> recs, Path dest) throws IOException {
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dest), 1 << 16),
                StandardCharsets.UTF_8)) {
            w.write('[');
            boolean first = true;
            for (Map.Entry<Integer, String[]> e : recs.entrySet()) {
                if (!first) {
                    w.write(',');
                }
                first = false;
                w.write('[');
                w.write(jsonString(new String(Character.toChars(e.getKey()))));
                w.write(',');
                w.write(jsonString(e.getValue()[0]));
                w.write(',');
                w.write(jsonString(e.getValue()[1]));
                w.write(']');
            }
            w.write(']');
        }
    }

    // ------------------------------------------------------------------ segmentation

    static final class SegStats {
        long sourceLines;
        long terms;
        long totalFrequency;
    }

    static SegStats writeSegmentation(Source jieba, Path dest, int minLines, int minTerms) throws IOException {
        SegStats st = new SegStats();
        try (BufferedReader r = utf8Reader(Files.newInputStream(jieba.path));
             Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dest), 1 << 16),
                     StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                st.sourceLines++;
                if (st.sourceLines == 1) {
                    line = line.replace("\uFEFF", "");
                }
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String[] p = t.split("\\s+");
                if (p.length < 2) {
                    continue;
                }
                String word = p[0];
                long freq;
                try {
                    freq = Long.parseLong(p[1]);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (freq < 1 || freq > Integer.MAX_VALUE) {
                    continue;
                }
                int len = word.codePointCount(0, word.length());
                if (len < 1 || len > 12 || !HAN_ONLY.matcher(word).matches()) {
                    continue;
                }
                w.write(word);
                w.write('\t');
                w.write(Long.toString(freq));
                w.write('\n');
                st.terms++;
                st.totalFrequency += freq;
            }
        } catch (IOException ex) {
            onInvalidDownload(jieba);
            throw new IOException(jieba.name + ": could not be read as UTF-8 text: " + ex.getMessage(), ex);
        }
        if (st.sourceLines < minLines) {
            onInvalidDownload(jieba);
            throw new IOException(jieba.name + ": only " + st.sourceLines + " lines; at least " + minLines + " are required");
        }
        if (st.terms < minTerms) {
            onInvalidDownload(jieba);
            throw new IOException(jieba.name + ": only " + st.terms + " usable terms; at least " + minTerms + " are required");
        }
        return st;
    }

    /**
     * Decodes the written gzip back from disk and checks every row. This catches literal "\t" escapes, non-Han tokens,
     * non-positive frequencies, and any mismatch between the row count and the frequency total.
     */
    static void verifySegmentation(Path gz, long expectedTerms, long expectedTotal) throws IOException {
        long rows = 0;
        long total = 0;
        try (BufferedReader r = utf8Reader(new GZIPInputStream(Files.newInputStream(gz), 1 << 16))) {
            String line;
            while ((line = r.readLine()) != null) {
                rows++;
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.indexOf('\t', tab + 1) >= 0) {
                    throw new IOException("Segmentation row " + rows + " is not tab-separated into two fields");
                }
                String word = line.substring(0, tab);
                if (!HAN_ONLY.matcher(word).matches() || word.codePointCount(0, word.length()) > 12) {
                    throw new IOException("Segmentation row " + rows + " has an invalid word: " + word);
                }
                long f;
                try {
                    f = Long.parseLong(line.substring(tab + 1));
                } catch (NumberFormatException ex) {
                    throw new IOException("Segmentation row " + rows + " has a non-numeric frequency");
                }
                if (f < 1) {
                    throw new IOException("Segmentation row " + rows + " has a non-positive frequency");
                }
                total += f;
            }
        }
        if (rows != expectedTerms || total != expectedTotal) {
            throw new IOException("Segmentation read-back mismatch: rows " + rows + " (expected " + expectedTerms
                    + "), frequency total " + total + " (expected " + expectedTotal + ")");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void onInvalidDownload(Source s) {
        if (!s.fromUrl) {
            return;
        }
        try {
            Files.deleteIfExists(s.path);
        } catch (IOException ignored) {
            // Best effort: the next build revalidates and re-downloads anyway.
        }
    }

    private static String describe(Source s) {
        return s.fromUrl ? s.url : "local file: " + s.path.getFileName();
    }

    public static String sha256(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    static void writeJson(Object v, StringBuilder sb, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            sb.append(jsonString((String) v));
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                pad(sb, indent + 2);
                sb.append(jsonString(e.getKey())).append(": ");
                writeJson(e.getValue(), sb, indent + 2);
                if (++i < m.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                writeJson(list.get(i), sb, indent);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + v.getClass());
        }
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
    }

    static String buildNotice(Source cedict, Source unihan, Source jieba) {
        return String.join("\n",
                "NOCTURNE offline language data attribution",
                "",
                "CC-CEDICT",
                "The Chinese-English lexical data is from CC-CEDICT, maintained by MDBG and CC-CEDICT contributors,",
                "continuing the CEDICT project begun by Paul Andrew Denisowski.",
                "Packaged source: " + describe(cedict),
                "Project information: https://www.mdbg.net/chinese/dictionary?page=cc-cedict",
                "License: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0):",
                "https://creativecommons.org/licenses/by-sa/4.0/",
                "The app parses, indexes, and formats this data for offline lookup. Retain attribution and the",
                "share-alike terms for adapted CC-CEDICT data.",
                "",
                "Unicode Unihan",
                "Character properties are derived from the Unicode Unihan Database.",
                "Packaged source: " + describe(unihan),
                "License and terms: https://www.unicode.org/license.html",
                "",
                "Jieba word-frequency and segmentation data",
                "The bundled word-frequency lexicon is derived from Jieba's dictionary data.",
                "Packaged source: " + describe(jieba),
                "The bundled lexicon supplies frequency priors for word-boundary decoding only, not English definitions.",
                "Project: https://github.com/fxsjy/jieba",
                "License: MIT",
                "The MIT License (MIT)",
                "Copyright (c) 2013 Sun Junyi",
                "",
                "Permission is hereby granted, free of charge, to any person obtaining a copy of",
                "this software and associated documentation files (the \"Software\"), to deal in",
                "the Software without restriction, including without limitation the rights to",
                "use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of",
                "the Software, and to permit persons to whom the Software is furnished to do so,",
                "subject to the following conditions:",
                "",
                "The above copyright notice and this permission notice shall be included in all",
                "copies or substantial portions of the Software.",
                "",
                "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR",
                "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS",
                "FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR",
                "COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER",
                "IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN",
                "CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.",
                "",
                "Broad built-in character pinyin fallback",
                "The compact character-to-pinyin lookup in the app was generated from the xpinyin database",
                "distribution, which states that its database was generated from Unicode Unihan_Readings.txt.",
                "Source project: https://github.com/lxneng/xpinyin",
                "License stated by xpinyin-database.def: LaTeX Project Public License (LPPL) 1.3c or later:",
                "https://www.latex-project.org/lppl/lppl-1-3c.txt",
                "The fallback selects a character-level reading; it cannot resolve a word's context-specific pronunciation.",
                "");
    }
}
