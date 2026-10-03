plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT"
    id("maven-publish")
}

fun prop(name: String): String = project.property(name).toString()

version = prop("mod_version")
group = prop("maven_group")
base { archivesName.set(prop("archives_base_name")) }

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net") { name = "FabricMC" }
    maven("https://maven.fallenbreath.me/releases") { name = "FallenBreath" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
    maven("https://masa.dy.fi/maven") { name = "Masa" }
    maven("https://masa.dy.fi/maven/sakura-ryoko") { name = "SakuraRyoko" }
    maven("https://jitpack.io") { name = "Jitpack" }
    maven(rootProject.file("localrepo")) {
        content { includeGroup("dev.blinkwhite.remoteinventory") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${prop("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_version")}")
    modImplementation("com.belerweb:pinyin4j:${prop("pinyin_version")}")?.let { include(it) }
    modImplementation("com.terraformersmc:modmenu:${prop("modmenu")}")

    // 运行时依赖：原版 Litematica + MaLiLib（不打包）
    modImplementation("fi.dy.masa.malilib:${prop("malilib")}")
    modImplementation("fi.dy.masa.litematica:${prop("litematica")}")

    // 可选联动：仅编译期可见，运行时按需检测
    modCompileOnly("fi.dy.masa.tweakeroo:tweakeroo-fabric-1.21.11:0.27.15")
    modCompileOnly(files("libs/quickshulker-2.10.0-1.21.11.jar"))
    modCompileOnly("me.fallenbreath:conditional-mixin-fabric:0.6.4")
    modCompileOnly(files("libs/schematicpreview-0.0.17+1.21.11.jar"))
    modCompileOnly(files("libs/shulkerbox-fabric-1.21.11-2.0.5.jar"))
    modCompileOnly("dev.blinkwhite.remoteinventory:remote-inventory-next:${prop("remote_inventory_version")}+${prop("minecraft_version")}")

    // 仅 gametest 运行时加载的可选联动模组（用于测试联动功能）
    if (providers.gradleProperty("aleGameTest").isPresent) {
        modLocalRuntime(files("libs/bedrock-miner-v1.6.1-mc1.21.11.jar"))
        modLocalRuntime(files("libs/shulkerbox-fabric-1.21.11-2.0.5.jar"))
        if (providers.gradleProperty("withLxyan").isPresent) {
            modLocalRuntime(files("libs/bedrock-miner-2.0.11+1.21.11.jar"))
            modLocalRuntime("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
        }
    }

    compileOnly("org.projectlombok:lombok:${prop("lombok_version")}")
    annotationProcessor("org.projectlombok:lombok:${prop("lombok_version")}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to prop("mod_id"),
        "mod_name" to prop("mod_name"),
        "mod_version" to prop("mod_version"),
        "minecraft_version" to prop("minecraft_version"),
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "*.mixins.json")) { expand(props) }
}

tasks.jar {
    from("LICENSE.md") { rename { "${it}_${prop("archives_base_name")}" } }
}

loom {
    runs {
        named("client") {
            programArguments.addAll(listOf("--width", "1280", "--height", "720", "--username", "ALETest"))
            runDir("run/client")
        }
    }
}

// 客户端 GameTest：./gradlew runClientGameTest -PaleGameTest
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
        for (key in listOf("range", "layered", "bpt", "ticks", "debuglook", "list", "debugrails", "lag", "protocol", "bigmats", "lagspike", "lagcheck", "noghost", "dumpmissing", "debugfluid", "backends")) {
            project.findProperty(key)?.let { (this as JavaExec).systemProperty("ale.$key", it.toString()) }
        }
        // 打印机本来就会直接发包（转头、潜行、容器操作），测试框架的网络同步检查会偶发误报：统一关掉
        (this as JavaExec).systemProperty("fabric.client.gametest.disableNetworkSynchronizer", "true")
    }
}
