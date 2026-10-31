import mihon.buildlogic.AndroidConfig
import mihon.buildlogic.configureTest
import mihon.buildlogic.libs

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("mihon.code.lint")
}

kotlin {
    android {
        compileSdk = AndroidConfig.COMPILE_SDK
        minSdk = AndroidConfig.MIN_SDK
        enableCoreLibraryDesugaring = true
        androidResources.enable = true
        withHostTest {}
        compilerOptions {
            jvmTarget.set(AndroidConfig.JvmTarget)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(false))
        }
    }
}

dependencies {
    "coreLibraryDesugaring"(libs.desugar)
}

configureTest()
