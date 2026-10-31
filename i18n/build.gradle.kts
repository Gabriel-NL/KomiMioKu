import mihon.buildlogic.tasks.LocalesConfigTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("mihon.library.multiplatform")
    alias(libs.plugins.moko)
    id("com.github.ben-manes.versions")
}

kotlin {
    android {
        namespace = "tachiyomi.i18n"
        lint {
            disable.addAll(listOf("MissingTranslation", "ExtraTranslation"))
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.core)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

multiplatformResources {
    resourcesPackage.set("tachiyomi.i18n")
}

val localesConfigTask = tasks.register<LocalesConfigTask>("generateLocalesConfig") {
    stringFiles.from(fileTree("src/commonMain/moko-resources") { include("**/strings.xml") })
    outputDirectory.set(layout.buildDirectory.dir("generated/mihon/android/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addStaticSourceDirectory("src/commonMain/resources")
        variant.sources.res?.addGeneratedSourceDirectory(localesConfigTask, LocalesConfigTask::outputDirectory)
    }
}
