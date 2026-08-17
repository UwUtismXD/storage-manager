pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    // Applies the right loom variant per version: fabric-loom-remap on 1.21.8 (obfuscated,
    // needs Mojang mappings) and plain fabric-loom on 26.1.2 (ships official names).
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        versions("1.21.8", "26.1.2", "26.2")
        // The version the source tree is checked in as - keeps 1.21.8 the one you read in git.
        vcsVersion = "1.21.8"
    }
}

rootProject.name = "storage-manager"
