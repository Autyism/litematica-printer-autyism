plugins {
    // Applies fabric-loom-remap up to 1.21.11 and fabric-loom on 26.1+ (unobfuscated)
    id("dev.kikugie.loom-back-compat")
}

fun prop(name: String): String = project.property(name).toString()

val mc = sc.current.version
val requiredJava = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25
    else if (sc.current.parsed >= "1.20.5") JavaVersion.VERSION_21 else JavaVersion.VERSION_17
// 1.20.1: MaLiLib, Litematica and Tweakeroo come from Modrinth (Masa's maven doesn't have them all for 1.20.1)
fun masa(id: String, version: String) = if (sc.current.parsed < "1.21") "maven.modrinth:$id:$version" else "fi.dy.masa.$id:$id-fabric-$mc:$version"

version = "${prop("mod.version")}+$mc"
group = prop("mod.group")
base { archivesName.set(prop("mod.id")) }

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net") { name = "FabricMC" }
    maven("https://maven.fallenbreath.me/releases") { name = "FallenBreath" } // conditional-mixin, needed by Litematica
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
    maven("https://jitpack.io") { name = "Jitpack" }
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
    maven("https://masa.dy.fi/maven") { name = "Masa" }
    maven("https://masa.dy.fi/maven/sakura-ryoko") { name = "SakuraRyoko" }
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
    modImplementation("com.belerweb:pinyin4j:${prop("deps.pinyin")}")?.let { include(it) }
    modImplementation("com.terraformersmc:modmenu:${prop("deps.modmenu")}")

    // Litematica + MaLiLib at runtime (not bundled)
    modImplementation(masa("malilib", prop("deps.malilib")))
    modImplementation(masa("litematica", prop("deps.litematica")))

    // Optional integrations: compile-time only, detected at runtime
    modCompileOnly(masa("tweakeroo", prop("deps.tweakeroo")))
    modCompileOnly(files(rootProject.file("libs/remote-inventory/remote-inventory-next-mc$mc-${prop("deps.remote_inventory")}.jar")))

    // Optional mods loaded only in gametests, to test the integrations (test runtime only, never bundled): official builds from Modrinth
    if (providers.gradleProperty("aleGameTest").isPresent) {
        // Bedrock Miner (bunnyi116): 1.6.1; 26.3 only has 1.6.2
        val bedrockMiner = mapOf("1.20.1" to "v1.6.1-mc1.20.1", "1.21.5" to "v1.6.1-mc1.21.5", "1.21.8" to "v1.6.1-mc1.21.8", "1.21.10" to "v1.6.1-mc1.21.10",
            "1.21.11" to "v1.6.1-mc1.21.11", "26.1.2" to "v1.6.1-mc26.1", "26.2" to "v1.6.1-mc26.2", "26.3" to "v1.6.2-mc26.3")
        // Advanced Shulkerboxes 2.0.5 (Modrinth version ids); no 26.3 release exists
        val shulkerbox = mapOf("1.21.5" to "Y8mwwbAg", "1.21.8" to "IpebOJTB", "1.21.10" to "k1b88TtL", "1.21.11" to "qW4ksxvD",
            "26.1.2" to "hO8yONQQ", "26.2" to "gwthW8Gh")
        bedrockMiner[mc]?.let { modLocalRuntime("maven.modrinth:next-fabric-bedrock-miner:$it") }
        shulkerbox[mc]?.let { modLocalRuntime("maven.modrinth:advanced-shulkerboxes:$it") }
        // Fabric-Bedrock-Miner (LXYan2333, needs Kotlin) instead, with -PwithLxyan: 2.0.11 for 1.21.11
        if (providers.gradleProperty("withLxyan").isPresent && mc == "1.21.11") {
            modLocalRuntime("maven.modrinth:fabric-bedrock-miner:3h4YNmtb")
            modLocalRuntime("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
        }
    }

    compileOnly("org.projectlombok:lombok:${prop("deps.lombok")}")
    annotationProcessor("org.projectlombok:lombok:${prop("deps.lombok")}")
}

java {
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    // 1.20.1 targets Java 17: compiled by JDK 21 with --release 17 (same class files, no JDK 17 needed)
    toolchain { languageVersion.set(JavaLanguageVersion.of(maxOf(requiredJava.majorVersion.toInt(), 21))) }
}

// 1.20.1: the block highlight is drawn by render/LegacyBlockHighlightRenderer (old Tesselator API) instead
if (sc.current.parsed < "1.21") {
    sourceSets.main { java.exclude("com/autyism/printer/render/BlockHighlightRenderer.java") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // gametests are never shipped and run on the JDK 21 toolchain, so on 1.20.1 they may use Java 21 too
    options.release.set(if (name == "compileGametestJava") maxOf(requiredJava.majorVersion.toInt(), 21) else requiredJava.majorVersion.toInt())
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to prop("mod.id"),
        "mod_name" to prop("mod.name"),
        "mod_version" to prop("mod.version"),
        "minecraft_version" to prop("mod.mc_compat"),
        "loader_compat" to prop("mod.loader_compat"),
        "malilib_compat" to prop("mod.malilib_compat"),
        "litematica_compat" to prop("mod.litematica_compat"),
        "mixin_java" to "JAVA_${requiredJava.majorVersion}",
        // 1.20.1 only (empty on other versions): single-player reach without the block_interaction_range attribute,
        // and creative inventory echoes that newer versions no longer send
        "legacy_mixins" to (if (sc.current.parsed < "1.21") listOf("MixinLegacyReachUseItemOn", "MixinLegacyReachBreak", "MixinLegacyReachItem",
            "MixinLegacyReachPick", "MixinLegacyReachSign", "MixinLegacyCreativeSend", "MixinLegacyCreativeEcho")
            .joinToString("") { ",\n    \"mc.$it\"" } else ""),
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "*.mixins.json")) { expand(props) }
}

tasks.withType<Jar>().configureEach {
    val baseName = prop("mod.id")
    from(rootProject.file("LICENSE.md")) { rename { "${it}_$baseName" } }
}

loom {
    runs {
        named("client") {
            programArguments.addAll(listOf("--width", "1280", "--height", "720", "--username", "ALETest"))
            runDir("../../run/client")
        }
    }
}

// Collects the release jars of all versions in build/libs/<mod version>/
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/${prop("mod.version")}"))
}

// Client GameTest: ./gradlew :1.21.11:runClientGameTest -PaleGameTest
if (providers.gradleProperty("aleGameTest").isPresent) {
    fabricApi {
        configureTests {
            createSourceSet.set(true)
            modId.set("litematica-printer-autyism-gametest")
            enableGameTests.set(false)
            enableClientGameTests.set(true)
            eula.set(true)
            clearRunDirectory.set(true)
            username.set("ALEGameTest")
        }
    }
    tasks.matching { it.name == "runClientGameTest" }.configureEach {
        (this as JavaExec).systemProperty("ale.gt", (project.findProperty("gt") ?: "").toString())
        for (key in listOf("range", "layered", "bpt", "ticks", "debuglook", "list", "debugrails", "lag", "protocol", "bigmats", "lagspike", "lagcheck", "noghost", "dumpmissing", "debugfluid", "backends", "slotdedupe", "startindex")) {
            project.findProperty(key)?.let { (this as JavaExec).systemProperty("ale.$key", it.toString()) }
        }
        // 打印机本来就会直接发包（转头、潜行、容器操作），测试框架的网络同步检查会偶发误报：统一关掉
        (this as JavaExec).systemProperty("fabric.client.gametest.disableNetworkSynchronizer", "true")
    }
}
