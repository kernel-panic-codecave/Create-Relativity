plugins {
    id("dev.architectury.loom") version "1.17.493"
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    // Archie's build plugin: stamps Archie's library deps as FML game libraries so the dev runtime loads them
    id("net.kernelpanicsoft.archie.archie-plugin") version "1.0.9-SNAPSHOT"
}

group   = property("mod_group").toString()
version = property("mod_version").toString()

val modId = property("mod_id").toString()
val mcVersion = property("minecraft_version").toString()

loom {
    neoForge {}

    // Reuse Loom's implicit "main" mod group: a second group sharing build/resources/main
    // makes FML pick "main", whose classpath lacks build/classes/kotlin/main.
    // physics-core is added to the same group so its classes live in our mod's module in dev;
    // the plain classpath isn't visible to FML's game layer.
    mods {
        maybeCreate("main").apply {
            sourceSet(sourceSets.main.get())
            sourceSet("main", project(":physics-core"))
        }
    }

    runs {
        named("client")
        named("server")
        // Archie GameTest runs: `./gradlew runGametest` (server suite, exits with the result).
        create("gametest") {
            server()
            name("Minecraft GameTest")
            property("neoforge.enableGameTest", "true")
            property("neoforge.gameTestServer", "true")
            property("archie.gametest.side", "server")
            property("archie.gametest", "true")
            property("archie.gametest.modid", modId)
            // Only ours: other mods on the dev runtime ship gametests whose structures aren't here.
            property("neoforge.enabledGameTestNamespaces", modId)
        }
        create("gametestClient") {
            client()
            name("Minecraft GameTest Client")
            property("neoforge.enableGameTest", "true")
            property("archie.gametest.side", "client")
            // (Lens switches passed on from Gradle's own: `./gradlew runGametestClient -Drelativity.imageStage=true`.)
            System.getProperty("relativity.imageStage")?.let { property("relativity.imageStage", it) }
            System.getProperty("relativity.tessellation")?.let { property("relativity.tessellation", it) }
            System.getProperty("relativity.tessellationTable")?.let { property("relativity.tessellationTable", it) }
            property("archie.gametest", "true")
            property("archie.gametest.modid", modId)
            property("neoforge.enabledGameTestNamespaces", modId)
        }
        forEach { config ->
            config.ideConfigGenerated(true)
        }
    }
}

repositories {
    mavenCentral()
    google() // AndroidX libraries under Archie's Compose runtime
    exclusiveContent { // Archie snapshots are published to mavenLocal from ~/IdeaProjects/Archie
        forRepositories(
            mavenLocal(),
            maven("https://maven.kernelpanicsoft.net/releases") { name = "KernelPanic Releases" },
            maven("https://maven.kernelpanicsoft.net/snapshots") { name = "KernelPanic Snapshots" },
        )
        filter { includeGroup("net.kernelpanicsoft.archie") }
    }
    maven("https://maven.architectury.dev/")      { name = "Architectury" }
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    maven("https://maven.parchmentmc.org")        { name = "Parchment" }
    maven("https://libraries.minecraft.net/")     { name = "Minecraft" }
    maven("https://maven.createmod.net")          { name = "Create" }
    maven("https://maven.tterrag.com")            { name = "Tterrag" }
    maven("https://maven.ithundxr.dev/releases")  { name = "IThundXr" }
    maven("https://maven.ithundxr.dev/snapshots") { name = "IThundXr Snapshots (Registrate)" }
    maven("https://maven.ithundxr.dev/hidden")    { name = "IThundXr Hidden" }
    maven("https://maven.ithundxr.dev/mirror")    { name = "IThundXr Mirror" }
    maven("https://maven.blamejared.com")         { name = "BlameJared (Veil)" }
    maven("https://maven.shedaniel.me")           { name = "Shedaniel (Cloth Config)" }
    maven("https://maven.teamresourceful.com/repository/maven-public") { name = "Resourceful (Common Storage Lib)" }
    maven("https://repo.nyon.dev/releases")       { name = "Nyon" }
    exclusiveContent {
        forRepository { maven("https://maven.ryanhcode.dev/releases") { name = "RyanHCode (Sable)" } }
        filter {
            includeGroup("dev.ryanhcode.sable")
            includeGroup("dev.ryanhcode.sable-companion")
            includeGroup("dev.simulated_team.simulated")
            includeGroup("dev.eriksonn.aeronautics")
        }
    }
    exclusiveContent {
        forRepository { maven("https://maven.caffeinemc.net/releases") { name = "CaffeineMC (Sodium)" } }
        filter { includeGroup("net.caffeinemc") }
    }
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:$mcVersion")
    "mappings"(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$mcVersion:${property("parchment_version")}@zip")
    })
    "neoForge"("net.neoforged:neoforge:${property("neoforge_version")}")

    implementation("dev.nyon:KotlinLangForge:${property("klf_version")}+neoforge")
    // Provided at runtime by KotlinLangForge (jar-in-jar); needed to compile @Serializable packets/attachments
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")

    // Archie: config, networking, saved state. Transitive on purpose: Loom's remap drops its
    // jar-in-jar metadata, so its libraries (patched by archie-plugin) must come in as dependencies.
    // Brings Architectury API, Cloth Config and Common Storage Lib along.
    modImplementation("net.kernelpanicsoft.archie:archie-core-neoforge:${property("archie_version")}") {
        exclude(group = "dev.nyon", module = "KotlinLangForge")
        exclude(group = "org.junit.jupiter")
    }

    // Archie's GameTest harness: dev-time only, referenced only behind AGameTestPlatform.isGameTest
    modCompileOnly("net.kernelpanicsoft.archie:archie-core-gametest-neoforge:${property("archie_version")}") { isTransitive = false }
    modLocalRuntime("net.kernelpanicsoft.archie:archie-core-gametest-neoforge:${property("archie_version")}") { isTransitive = false }

    // Pure-Kotlin relativity math, shipped inside our jar
    implementation(project(":physics-core"))
    include(project(":physics-core"))

    // Create
    modImplementation("com.simibubi.create:create-$mcVersion:${property("create_version")}:slim") { isTransitive = false }
    modImplementation("net.createmod.ponder:ponder-neoforge:${property("ponder_version")}+mc$mcVersion")
    modCompileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-$mcVersion:${property("flywheel_version")}")
    modRuntimeOnly("dev.engine-room.flywheel:flywheel-neoforge-$mcVersion:${property("flywheel_version")}")
    modImplementation("com.tterrag.registrate:Registrate:${property("registrate_version")}")

    // Sable physics + Veil rendering
    // Non-transitive because Sable's runtime POM pulls the full Create jar alongside our slim one;
    // its real runtime deps are listed explicitly instead.
    modImplementation("dev.ryanhcode.sable:sable-neoforge-$mcVersion:${property("sable_version")}") { isTransitive = false }
    modImplementation("dev.ryanhcode.sable-companion:sable-companion-common-$mcVersion:${property("sable_companion_version")}") { isTransitive = false }
    modRuntimeOnly("dev.ryanhcode.sable:sable-sable_rapier-$mcVersion:${property("sable_version")}") { isTransitive = false }
    modImplementation("foundry.veil:veil-neoforge-$mcVersion:${property("veil_version")}") { isTransitive = false }
    // Sodium and Iris, to compile our hooks into them against (QuadSubdivision on Sodium's chunk meshing; Iris's shader
    // packs); the hooks apply only when they're loaded (CompatMixinPlugin).
    modCompileOnly("net.caffeinemc:sodium-neoforge-mod:0.8.13+mc$mcVersion") { isTransitive = false }
    modCompileOnly("maven.modrinth:iris:1.8.14-beta.1+$mcVersion-neoforge") { isTransitive = false }
    "forgeRuntimeLibrary"("gg.moonflower:molang-compiler:${property("veil_molang_version")}")
    compileOnly("gg.moonflower:molang-compiler:${property("veil_molang_version")}") // (the lens resolution setting reaches Veil's post pipelines as a Molang query)
    "forgeRuntimeLibrary"("io.github.ocelot:glsl-processor:${property("veil_glsl_processor_version")}")
    compileOnly("io.github.ocelot:glsl-processor:${property("veil_glsl_processor_version")}") // (the lens's Veil shader processor works on its trees)

    // Simulated + Aeronautics as individual jars: Loom's remap of the Modrinth "bundled" jar drops
    // META-INF/jarjar/metadata.json, so its nested mods never load. Non-transitive because their
    // POMs list optional integrations (JEI, Curios, CC: Tweaked, compasses) as hard deps.
    modImplementation("dev.simulated_team.simulated:simulated-neoforge-$mcVersion:${property("simulated_version")}") { isTransitive = false }
    modRuntimeOnly("dev.eriksonn.aeronautics:aeronautics-neoforge-$mcVersion:${property("simulated_version")}") { isTransitive = false }

}

// Dev-only compatibility runs (nothing shipped): -PwithSodium adds Sodium (its own chunk renderer and shaders),
// -PwithIris adds Sodium and Iris (shader packs, from run/shaderpacks). Copied into run/mods, where Sodium's own
// locator loads the real mod nested in its jar: through Loom's runtime classpath (Modrinth's jar, or the maven dev
// artifacts with Forgified Fabric API) its API and mod end up in different layers and the game won't start.
val compatMods: Configuration = configurations.create("compatMods") { isTransitive = false }
val withIris = providers.gradleProperty("withIris").isPresent
val withSodium = withIris || providers.gradleProperty("withSodium").isPresent
dependencies {
    if (withSodium) compatMods("maven.modrinth:sodium:mc$mcVersion-0.8.13-neoforge")
    if (withIris) compatMods("maven.modrinth:iris:1.8.14-beta.1+$mcVersion-neoforge")
}
val syncCompatMods by tasks.registering {
    description = "Puts the -PwithSodium / -PwithIris mods in run/mods (as compat-*.jar), or takes them out."
    val files = compatMods
    val mods = layout.projectDirectory.dir("run/mods").asFile
    inputs.files(files)
    doLast {
        mods.mkdirs()
        mods.listFiles { file -> file.name.startsWith("compat-") }?.forEach { it.delete() }
        for (file in files) file.copyTo(mods.resolve("compat-" + file.name))
    }
}
tasks.matching { it.name.startsWith("run") }.configureEach { dependsOn(syncCompatMods) }


kotlin {
    jvmToolchain(21)
}

val modProps = mapOf(
    "mod_id" to modId,
    "mod_name" to property("mod_name").toString(),
    "mod_version" to version.toString(),
    "mod_authors" to property("mod_authors").toString(),
    "mod_license" to property("mod_license").toString(),
    "neoforge_version" to property("neoforge_version").toString(),
)

tasks.processResources {
    val props = modProps
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

// Compiles our Veil shaders offline (needs glslangValidator on PATH; skipped otherwise).
val checkShaders by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles Veil shaders with glslangValidator and flags patterns Veil mangles."
    val validatorPresent = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { File(it, "glslangValidator").canExecute() }
    onlyIf("glslangValidator is on PATH") { validatorPresent }
    inputs.dir("src/main/resources/assets/create_relativity/pinwheel/shaders")
    commandLine("tools/check-shaders.sh")
}
tasks.check { dependsOn(checkShaders) }
