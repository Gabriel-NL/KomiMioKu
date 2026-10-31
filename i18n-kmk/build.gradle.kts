plugins {
    id("mihon.library.multiplatform")
    alias(libs.plugins.moko)
    id("com.github.ben-manes.versions")
}

kotlin {
    android {
        namespace = "tachiyomi.i18n.kmk"
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
}

multiplatformResources {
    resourcesClassName.set("KMR")
    resourcesPackage.set("tachiyomi.i18n.kmk")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xexpect-actual-classes",
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addStaticSourceDirectory("src/commonMain/resources")
    }
}
