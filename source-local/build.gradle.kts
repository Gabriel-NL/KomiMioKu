import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("mihon.library.multiplatform")
}

kotlin {
    android {
        namespace = "tachiyomi.source.local"
        optimization.consumerKeepRules.files.add(file("consumer-rules.pro"))
    }
    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(projects.sourceApi)
                api(projects.i18n)
                // SY -->
                api(projects.i18nSy)
                api(projects.i18nKmk)
                // SY <--

                implementation(libs.unifile)
            }
        }
        val androidHostTest = getByName("androidHostTest") {
            kotlin.srcDir("src/androidUnitTest/kotlin")
            dependencies {
                implementation(libs.bundles.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        val androidMain = getByName("androidMain") {
            dependencies {
                implementation(projects.core.archive)
                implementation(projects.core.common)
                implementation(projects.coreMetadata)

                // Move ChapterRecognition to separate module?
                implementation(projects.domain)

                implementation(kotlinx.bundles.serialization)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}
