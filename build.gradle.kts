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
    // vendored locally - the 1.21.8 API jar under libs/, Meteor's 26.x forks under jars/.
    flatDir { dirs(rootProject.file("libs"), rootProject.file("jars")) }

    maven("https://maven.meteordev.org/releases") { name = "Meteor Releases" }
    maven("https://maven.meteordev.org/snapshots") { name = "Meteor Snapshots" }
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

    // Same deal for Meteor: compiled against, never bundled - the user's own install provides it
    // at runtime, and the mod still runs (web UI only) when it isn't there. Non-transitive because
    // Meteor pulls in auth/proxy libraries from third-party repos that nothing in the addon touches.
    val meteorVersion: String = sc.properties["deps.meteor_client"]
    modCompileOnly("meteordevelopment:meteor-client:$meteorVersion") { isTransitive = false }
    // Meteor's event bus - the one transitive dependency the addon does need, for @EventHandler.
    // Every Meteor line built here uses the same release.
    compileOnly("meteordevelopment:orbit:0.2.4")

    // Tier 1 (pure logic) + tier 2 (test doubles) JUnit 5 tests. Tier 3 would need live minecraft.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
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
