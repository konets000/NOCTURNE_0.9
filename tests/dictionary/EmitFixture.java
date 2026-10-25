package com.nocturne.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes a complete synthetic dictionary (1,200 CC-CEDICT lines, so the app's 1,000-entry minimum is met)
 * for the cross-language contract check. Usage: EmitFixture <output-root>
 */
public final class EmitFixture {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Path fixtures = Files.createDirectories(root.resolve("fx"));
        DictionaryPackager.Options o = PackagerSelfTest.baseOptions(root, "emit");
        o.cedictOverride = PackagerSelfTest.cedictFixture(fixtures, 1200, "c.txt.gz");
        o.unihanOverride = PackagerSelfTest.unihanFixture(fixtures);
        o.jiebaOverride = PackagerSelfTest.jiebaFixture(fixtures);
        DictionaryPackager.run(o);
        System.out.println("emitted " + o.outputDir.resolve("dictionary"));
    }
}
