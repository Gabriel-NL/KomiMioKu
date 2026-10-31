package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private val emptyResourcesElement = "<resources>\\s*</resources>|<resources\\s*/>".toRegex()

@DisableCachingByDefault(because = "Small generated locale list")
abstract class LocalesConfigTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = stringFiles.files
            .filterNot { it.readText().contains(emptyResourcesElement) }
            .map {
                it.parentFile.name
                    .replace("base", "en")
                    .replace("-r", "-")
                    .replace("+", "-")
            }
            .sorted()
            .joinToString("\n") { "|   <locale android:name=\"$it\"/>" }

        val content = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
        $locales
        |</locale-config>
        """.trimMargin()

        outputDirectory.file("xml/locales_config.xml").get().asFile.apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
