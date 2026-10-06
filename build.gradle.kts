plugins {
    // Applies fabric-loom-remap up to 1.21.11 and fabric-loom on 26.1+ (unobfuscated)
    id("dev.kikugie.loom-back-compat")
}

fun prop(name: String): String = project.property(name).toString()

val mc = sc.current.version
val requiredJava = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

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
    modImplementation("fi.dy.masa.malilib:malilib-fabric-$mc:${prop("deps.malilib")}")
    modImplementation("fi.dy.masa.litematica:litematica-fabric-$mc:${prop("deps.litematica")}")

    // Optional integrations: compile-time only, detected at runtime
    modCompileOnly("fi.dy.masa.tweakeroo:tweakeroo-fabric-$mc:${prop("deps.tweakeroo")}")
    modCompileOnly(files(rootProject.file("libs/remote-inventory/remote-inventory-next-mc$mc-${prop("deps.remote_inventory")}.jar")))

    // Optional mods loaded only in gametests (to test the integrations); only built for 1.21.11 so far
    if (providers.gradleProperty("aleGameTest").isPresent && mc == "1.21.11") {
        modLocalRuntime(files(rootProject.file("libs/bedrock-miner-v1.6.1-mc1.21.11.jar")))
        modLocalRuntime(files(rootProject.file("libs/shulkerbox-fabric-1.21.11-2.0.5.jar")))
        if (providers.gradleProperty("withLxyan").isPresent) {
            modLocalRuntime(files(rootProject.file("libs/bedrock-miner-2.0.11+1.21.11.jar")))
            modLocalRuntime("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
        }
    }

    compileOnly("org.projectlombok:lombok:${prop("deps.lombok")}")
    annotationProcessor("org.projectlombok:lombok:${prop("deps.lombok")}")
}

java {
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain { languageVersion.set(JavaLanguageVersion.of(requiredJava.majorVersion)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(requiredJava.majorVersion.toInt())
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
