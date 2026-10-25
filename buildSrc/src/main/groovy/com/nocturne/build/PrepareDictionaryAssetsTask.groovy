package com.nocturne.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.util.function.Consumer

/**
 * Thin Gradle wrapper around DictionaryPackager. All logic, validation and tests live in the plain-Java class.
 *
 * Inputs are declared so Gradle re-packages only when a URL, pin, override, or the app version changes.
 * The download cache is an @Internal directory: it is reused across builds without invalidating the task.
 */
abstract class PrepareDictionaryAssetsTask extends DefaultTask {

    @Input
    abstract Property<String> getAppVersion()

    @Input
    abstract Property<String> getCedictUrl()

    @Input
    abstract Property<String> getUnihanUrl()

    @Input
    abstract Property<String> getJiebaUrl()

    @Input
    @Optional
    abstract Property<String> getCedictSha256()

    @Input
    @Optional
    abstract Property<String> getUnihanSha256()

    @Input
    @Optional
    abstract Property<String> getJiebaSha256()

    // Track the file contents as Gradle inputs, not merely the path string. Replacing an override
    // file at the same path must invalidate the generated dictionary assets.
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract RegularFileProperty getCedictOverrideFile()

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract RegularFileProperty getUnihanOverrideFile()

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract RegularFileProperty getJiebaOverrideFile()

    @Internal
    abstract DirectoryProperty getCacheDir()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @TaskAction
    void prepare() {
        def o = new DictionaryPackager.Options()
        o.cacheDir = cacheDir.get().asFile.toPath()
        o.outputDir = outputDir.get().asFile.toPath()
        o.appVersion = appVersion.get()

        o.cedictUrl = cedictUrl.get()
        o.unihanUrl = unihanUrl.get()
        o.jiebaUrl = jiebaUrl.get()

        o.cedictSha256 = cedictSha256.getOrNull()
        o.unihanSha256 = unihanSha256.getOrNull()
        o.jiebaSha256 = jiebaSha256.getOrNull()

        o.cedictOverride = cedictOverrideFile.isPresent() ? cedictOverrideFile.get().asFile.toPath() : null
        o.unihanOverride = unihanOverrideFile.isPresent() ? unihanOverrideFile.get().asFile.toPath() : null
        o.jiebaOverride = jiebaOverrideFile.isPresent() ? jiebaOverrideFile.get().asFile.toPath() : null

        o.log = { String message -> logger.lifecycle('NOCTURNE: ' + message) } as Consumer

        def result = DictionaryPackager.run(o)
        logger.lifecycle("NOCTURNE: packaged ${result.cedictEntries} CC-CEDICT entries, " +
                "${result.unihanRecords} Unihan records, and ${result.segmentationTerms} segmentation terms.")
    }
}
