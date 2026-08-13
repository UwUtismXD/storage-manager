plugins {
    // Applies the correct loom variant based on the Minecraft version.
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

repositories {
    // Baritone has no reliable public Maven repo for recent Fabric builds, so the jars are
    // vendored locally - the 1.21.8 API jar under libs/, Meteor's 26.1 fork under jars/.
    flatDir { dirs(rootProject.file("libs"), rootProject.file("jars")) }
}

dependencies {
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    // No-op on versions that already ship official names.
    loomx.applyMojangMappings()

    // Use `mod{dependency type}` even on 26.1+ - loom-back-compat converts them.
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    fapi("fabric-lifecycle-events-v1", "fabric-networking-api-v1", "fabric-resource-loader-v0")

    // Compile against the Baritone API only. The full mod is installed separately at runtime.
    // The String type is explicit because sc.properties is a generic getter and file() takes
    // Any, which would otherwise resolve the type parameter to Any and fail to convert.
    val baritoneJar: String = sc.properties["mod.baritone_jar"]
    modCompileOnly(files(rootProject.file(baritoneJar)))
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("storage-manager") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run") // Shared between versions
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
            register("java", "mod.java")
            // Real Baritone is "baritone"; Meteor's 26.1 fork registers "baritone-meteor".
            register("baritone", "mod.baritone_id")
        }

        filesMatching("fabric.mod.json") { expand(props) }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", project.property("mod.version"))
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
