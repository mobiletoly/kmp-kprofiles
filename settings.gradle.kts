pluginManagement {
    includeBuild("plugin")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.compose") version "1.10.3"
        id("com.android.application") version "9.1.0"
        id("com.android.kotlin.multiplatform.library") version "9.1.0"
        id("com.android.lint") version "9.1.0"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kmp-kprofiles"
include("sample-app")
include("sample-app:composeApp")
includeBuild("plugin")
