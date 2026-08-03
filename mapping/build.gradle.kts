plugins {
    `java-library`
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull?.takeIf { it.isNotBlank() }

// 单平台收敛：mapping 只以 windows 为基准（obf jar / 全量表 / named jar 均为 windows 侧），
// 跨平台运行时承载（natives / 启动环境）由 NanoForge 负责；需要显式覆盖时传 -Pstarsector.platform=<platform>。
val mappingPlatform = providers.gradleProperty("starsector.platform").orElse("windows")
val namedGameJarsDir = mappingPlatform.map { platform ->
    rootProject.layout.buildDirectory.dir("named-game-jars/$platform").get().asFile
}
/** named 游戏 jar 本地 Maven 仓库目录（app 以模块依赖消费，IDEA 同步时自动附加 sources）。 */
val namedGameRepoDir = mappingPlatform.map { platform ->
    rootProject.layout.buildDirectory.dir("named-game-repo/$platform").get().asFile
}
/**
 * 游戏本体 jar 基名（vendored obf jar，remap 后进入 named 命名空间）。
 * 第三方 jar remap 时原样透传，经 passthroughGameJars 扫描后以 group starsector.game
 * 发布进 named repo，供下游（SDG）按 Maven 坐标消费。
 */
val gameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

/** named 构件统一版本（SNAPSHOT + app 端 cacheChangingModulesFor(0)：mapping 变更重发布后 IDE 同步即取新 jar）。 */
val namedGameVersion = "0.98a-RC8-SNAPSHOT"

/** 发布物名称（Gradle 任务名片段）：starfarer_obf → namedStarfarerObf。 */
fun namedGamePublicationName(baseName: String): String =
    "named" + baseName.split('.', '_').joinToString("") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

/**
 * 透传第三方 jar 的发布物名称（Gradle 任务名片段）：
 * xstream-1.4.21_miko → thirdPartyXstream1421Miko（artifactId 内 . _ - 均作单词分隔）。
 */
fun thirdPartyPublicationName(artifactId: String): String =
    "thirdParty" + artifactId.split('.', '_', '-').joinToString("") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

/** 全量映射生成物目录（mapping/build/generated/mappings/{platform}/，生成物不入库）。 */
val generatedMappingsDir = layout.buildDirectory.dir("generated/mappings")
/** 映射报告目录（mapping/build/reports/，报告不入库）。 */
val mappingReportsDir = layout.buildDirectory.dir("reports")
/** 当前平台的全量映射文件（由 generateFullMappings 产出）。 */
val fullMappingFile = mappingPlatform.map { platform ->
    generatedMappingsDir.get().file("$platform/ssoptimizer-$platform-full.tiny").asFile
}

fun resolveGameJarDirectory(gameDirPath: String): File {
    val gameDir = file(gameDirPath)
    val starsectorCoreDir = gameDir.resolve("starsector-core")
    return if (starsectorCoreDir.isDirectory) starsectorCoreDir else gameDir
}

/**
 * CI 模式下使用的游戏 classpath 第三方依赖——与 Starsector 0.98a-RC8 运行时版本对齐。
 * 当 starsector.gameDir 未设置时，这些 jar 和 game-jars/{platform}/ 中的 vendor jar
 * 一起传入 remapper，替代从游戏安装目录读取 jar 的行为。
 */
val gameClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

/**
 * Vineflower 反编译器 classpath——把 named 游戏 jar 反编译为 -sources.jar，
 * 随本地仓库一同发布，IDEA 同步时自动附加为源码（替代内置反编译视图）。
 */
val vineflower: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    api("org.ow2.asm:asm:9.9.1")
    api("org.ow2.asm:asm-commons:9.9.1")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("org.ow2.asm:asm-tree:9.9.1")

    // Starsector 0.98a-RC8 运行时 classpath 上的第三方库（用于 remap 时的类层次解析）
    gameClasspath("org.lwjgl.lwjgl:lwjgl:2.9.3")
    gameClasspath("org.lwjgl.lwjgl:lwjgl_util:2.9.3")
    gameClasspath("com.thoughtworks.xstream:xstream:1.4.10")
    gameClasspath("org.codehaus.janino:janino:2.7.8")
    gameClasspath("org.codehaus.janino:commons-compiler:2.7.8")
    gameClasspath("org.codehaus.janino:commons-compiler-jdk:2.7.8")
    gameClasspath("log4j:log4j:1.2.9")
    gameClasspath("org.json:json:20231013")
    gameClasspath("javax.xml.bind:jaxb-api:2.4.0-b180830.0359")
    gameClasspath("org.glassfish.jaxb:txw2:3.0.2")
    gameClasspath("org.sejda.imageio:webp-imageio:0.1.6")
    gameClasspath("net.java.jinput:jinput:2.0.7")

    vineflower("org.vineflower:vineflower:1.12.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateFullMappings") {
    group = "mapping"
    description = "Generate full (placeholder + human) mappings for both platforms from vendored game jars"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.nanoforged.sourcesector.mapping.gen.FullMappingCli")

    val gameJarsRoot = rootProject.file("game-jars")
    val humanMappingsDir = file("src/main/resources/mappings")

    inputs.dir(gameJarsRoot)
    inputs.dir(humanMappingsDir)
    outputs.dir(generatedMappingsDir)
    outputs.dir(mappingReportsDir)

    doFirst {
        args(listOf(
            gameJarsRoot.absolutePath,
            humanMappingsDir.absolutePath,
            generatedMappingsDir.get().asFile.absolutePath,
            mappingReportsDir.get().asFile.absolutePath
        ))
    }
}

tasks.register<JavaExec>("mergeScopeFragments") {
    group = "mapping"
    description = "Validate scope mapping fragments (parse, jar consistency, cross-scope uniqueness) and write coverage report"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.nanoforged.sourcesector.mapping.gen.ScopeFragmentCli")

    val gameJarsRoot = rootProject.file("game-jars")
    val humanMappingsDir = file("src/main/resources/mappings")
    val reportFile = mappingReportsDir.map { it.file("scope-fragments.txt") }

    inputs.dir(gameJarsRoot)
    inputs.dir(humanMappingsDir)
    outputs.file(reportFile)

    doFirst {
        args(listOf(
            gameJarsRoot.absolutePath,
            humanMappingsDir.absolutePath,
            reportFile.get().asFile.absolutePath
        ))
    }
}

tasks.register<JavaExec>("validateScopeFragment") {
    group = "mapping"
    description = "Validate a single scope fragment file before submission: -Pfragment=<path-to-{scope}-{platform}.tiny>"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.nanoforged.sourcesector.mapping.gen.ScopeFragmentCli")

    val gameJarsRoot = rootProject.file("game-jars")
    val humanMappingsDir = file("src/main/resources/mappings")
    inputs.dir(gameJarsRoot)
    inputs.dir(humanMappingsDir)
    // 候选片段由 -Pfragment 传入且通常在工作区外，不参与增量缓存判定。
    outputs.upToDateWhen { false }

    doFirst {
        val fragment = providers.gradleProperty("fragment").orNull
            ?: throw GradleException("用法: ./gradlew :mapping:validateScopeFragment -Pfragment=<片段文件路径>")
        args(listOf(
            "--check",
            gameJarsRoot.absolutePath,
            humanMappingsDir.absolutePath,
            rootProject.file(fragment).absolutePath
        ))
    }
}

tasks.register<JavaExec>("remapGameClasspathToNamed") {
    group = "mapping"
    description = "Remap Starsector compile classpath jars to named namespace"
    dependsOn(tasks.named("classes"), "generateFullMappings")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.nanoforged.sourcesector.mapping.JarRemapCli")
    systemProperty("ssoptimizer.mapping.platform", mappingPlatform.get())

    // 消费构建期全量表（人工条目优先 + 占位名），tiny 源或生成器输入变更会触发重跑。
    inputs.files(fullMappingFile)
    if (starsectorGameDir != null) {
        inputs.dir(resolveGameJarDirectory(starsectorGameDir!!))
    } else {
        inputs.files(mappingPlatform.map { platform ->
            fileTree(rootProject.file("game-jars/$platform")) { include("*.jar") }
        })
        inputs.files(configurations["gameClasspath"])
    }
    outputs.dir(namedGameJarsDir)

    doFirst {
        val inputJars: List<File>
        if (starsectorGameDir != null) {
            // 本地开发模式：从游戏安装目录读取全部 jar
            val jarDir = resolveGameJarDirectory(starsectorGameDir!!)
            inputJars = fileTree(jarDir) {
                include("*.jar")
                exclude("*-sources.jar", "*-javadoc.jar")
            }
                .files
                .sortedBy { it.name }
            require(inputJars.isNotEmpty()) {
                "未在 Starsector 目录中找到可 remap 的 JAR: ${file(starsectorGameDir!!)} (resolvedJarDir=$jarDir)"
            }
        } else {
            // CI 模式：从 game-jars/{platform}/ 读取 vendor jar + Maven 解析第三方 jar
            val platform = mappingPlatform.get()
            val vendorDir = rootProject.file("game-jars/$platform")
            require(vendorDir.isDirectory) {
                "CI 模式下未找到 vendor jar 目录: $vendorDir — 请确认 game-jars/$platform/ 下有平台专属 jar"
            }
            val vendorJars = fileTree(vendorDir) { include("*.jar") }
                .files
                .sortedBy { it.name }
            require(vendorJars.isNotEmpty()) {
                "vendor jar 目录为空: $vendorDir"
            }
            val thirdPartyJars = configurations["gameClasspath"]
                .resolve()
                .sortedBy { it.name }
            inputJars = (vendorJars + thirdPartyJars).sortedBy { it.name }
            logger.lifecycle("[remapGameClasspathToNamed] CI 模式: ${vendorJars.size} vendor jar + ${thirdPartyJars.size} 第三方 jar")
        }

        val outputDir = namedGameJarsDir.get()
        outputDir.parentFile.mkdirs()
        outputDir.mkdirs()

        args(listOf(
            "--mapping=${fullMappingFile.get().absolutePath}",
            "batch", "obf-to-named", outputDir.absolutePath
        ) + inputJars.map { it.absolutePath })
    }
}

// 独立链接校验任务：消费 remap 产物，不阻塞 remap/发布链——当前产物已知存在
// 继承链名字分叉断裂，修复映射前接入发布链会直接失败；建议接入点见
// docs/design/named-jar-link-validation.md（修复后挂到 publishNamedGameJars 依赖链前）。
tasks.register<JavaExec>("verifyNamedJarLinks") {
    group = "mapping"
    description = "Verify member links inside named game jars (inheritance-chain name divergence -> NoSuchMethodError source)"
    dependsOn(tasks.named("classes"))
    // 校验消费 remap 产物：同一构建中两者都被调度时保证 remap 先行（不强制依赖，独立校验仍可用）。
    mustRunAfter("remapGameClasspathToNamed")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.nanoforged.sourcesector.mapping.gen.NamedJarLinkCli")

    val reportFile = mappingReportsDir.map { it.file("named-jar-link-violations.txt") }
    inputs.dir(namedGameJarsDir)
    outputs.file(reportFile)

    doFirst {
        val jarDir = namedGameJarsDir.get()
        require(jarDir.isDirectory) {
            "named 游戏 jar 目录不存在: $jarDir — 请先运行 :mapping:remapGameClasspathToNamed"
        }
        args(listOf(jarDir.absolutePath, reportFile.get().asFile.absolutePath))
    }
}

gameJarBaseNames.forEach { baseName ->
    val publicationName = namedGamePublicationName(baseName)
    tasks.register<JavaExec>("decompile${publicationName}ToSources") {
        group = "mapping"
        description = "Decompile named $baseName.jar with Vineflower into $baseName-sources.jar for IDE source attachment"
        dependsOn("remapGameClasspathToNamed")

        classpath = vineflower
        mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")

        val inputJar = mappingPlatform.map { platform ->
            rootProject.layout.buildDirectory.file("named-game-jars/$platform/$baseName.jar").get().asFile
        }
        val outputJar = mappingPlatform.map { platform ->
            rootProject.layout.buildDirectory.file("named-game-jars/$platform-sources/$baseName-sources.jar").get().asFile
        }
        inputs.file(inputJar)
        outputs.file(outputJar)

        doFirst {
            require(inputJar.get().isFile) {
                "named 游戏 jar 不存在: ${inputJar.get()} — 请先运行 :mapping:remapGameClasspathToNamed"
            }
            outputJar.get().parentFile.mkdirs()
            args(listOf("-dgs=1", inputJar.get().absolutePath, outputJar.get().absolutePath))
        }
    }
}

tasks.register("decompileNamedGameJars") {
    group = "mapping"
    description = "Decompile all named game jars with Vineflower into -sources.jar for IDE source attachment"
    gameJarBaseNames.forEach { baseName ->
        dependsOn("decompile${namedGamePublicationName(baseName)}ToSources")
    }
}

/**
 * 透传第三方 jar 发布清单——配置期扫描 remap 产物目录生成，不硬编码：
 * 取 named-game-jars/{platform}/ 下全部 *.jar，排除 4 个 named 主 jar 与 -sources.jar，
 * 剩余均为透传 jar（本地游戏目录 / CI gameClasspath 两来源在 remap 时原样落盘，文件名保留版本段）。
 * 目录尚未生成（首次构建）时清单为空，对应 publication 与发布任务均不注册。
 */
val passthroughGameJars: List<File> = namedGameJarsDir.get().let { dir ->
    if (!dir.isDirectory) {
        logger.lifecycle("[publishNamedGameJars] named 产物目录不存在，透传 jar 发布清单为空: $dir（先运行 :mapping:remapGameClasspathToNamed）")
        emptyList()
    } else {
        fileTree(dir) {
            include("*.jar")
            exclude(gameJarBaseNames.map { "$it.jar" })
            exclude("*-sources.jar")
        }.files.sortedBy { it.name }
    }
}

publishing {
    publications {
        gameJarBaseNames.forEach { baseName ->
            create<MavenPublication>(namedGamePublicationName(baseName)) {
                groupId = "starsector.named"
                artifactId = baseName
                // SNAPSHOT + app 端 cacheChangingModulesFor(0)：mapping 变更重发布后 IDE 同步即取新 jar
                version = namedGameVersion
                val platformId = mappingPlatform.get()
                artifact(rootProject.layout.buildDirectory
                    .file("named-game-jars/$platformId/$baseName.jar").get().asFile) {
                    builtBy(tasks.named("remapGameClasspathToNamed"))
                }
                artifact(rootProject.layout.buildDirectory
                    .file("named-game-jars/$platformId-sources/$baseName-sources.jar").get().asFile) {
                    classifier = "sources"
                    builtBy(tasks.named("decompileNamedGameJars"))
                }
            }
        }

        // 透传第三方 jar：group 归属 starsector.game（SDG 扫描该 group 自动挂 compileOnly），
        // artifactId 取 jar 文件名去 .jar（保留版本段，如 xstream-1.4.21_miko），version 与主 jar 一致。
        passthroughGameJars.forEach { jar ->
            val passthroughArtifactId = jar.name.removeSuffix(".jar")
            create<MavenPublication>(thirdPartyPublicationName(passthroughArtifactId)) {
                groupId = "starsector.game"
                artifactId = passthroughArtifactId
                version = namedGameVersion
                artifact(jar) {
                    builtBy(tasks.named("remapGameClasspathToNamed"))
                }
            }
        }

        // 全量 tiny 表构件（SDG reobfJar 等构建侧 remap 消费的映射源）
        create<MavenPublication>("fullMappings") {
            groupId = "starsector.named"
            artifactId = "mappings-${mappingPlatform.get()}"
            version = namedGameVersion
            artifact(fullMappingFile.get()) {
                extension = "tiny"
                builtBy(tasks.named("generateFullMappings"))
            }
        }

        // mapping 工具构件（JarRemapCli / BytecodeRemapper 等），供 SDG 以工具依赖消费
        create<MavenPublication>("mappingTool") {
            groupId = "io.github.nanoforged"
            artifactId = "sourcesector-mapping"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "namedGameRepo"
            url = namedGameRepoDir.get().toURI()
        }
    }
}

tasks.register("publishMappings") {
    group = "mapping"
    description = "Publish full tiny mapping table to local repo build/named-game-repo/{platform}"
    dependsOn("publishFullMappingsPublicationToNamedGameRepoRepository")
}

tasks.register("publishNamedGameJars") {
    group = "mapping"
    description = "Publish named game jars + decompiled sources + passthrough third-party jars to local repo build/named-game-repo/{platform}"
    // 链接校验门禁：named 产物成员链接断裂（继承链名字分叉）时发布直接失败。
    dependsOn("verifyNamedJarLinks")
    gameJarBaseNames.forEach { baseName ->
        val publicationTaskName = namedGamePublicationName(baseName)
            .replaceFirstChar { it.uppercase() }
        dependsOn("publish${publicationTaskName}PublicationToNamedGameRepoRepository")
    }
    // 透传第三方 jar 随 4 主 jar 一并发布进 named repo（清单为空时无对应任务，跳过）。
    passthroughGameJars.forEach { jar ->
        val publicationTaskName = thirdPartyPublicationName(jar.name.removeSuffix(".jar"))
            .replaceFirstChar { it.uppercase() }
        dependsOn("publish${publicationTaskName}PublicationToNamedGameRepoRepository")
    }
}

/**
 * 把 named 游戏 jar 安装进游戏目录（obf jar → mapping → named jar → 游戏目录）。
 *
 * 安装目标目录由 -Pstarsector.gameDir 指定（沿用 remapGameClasspathToNamed 的属性约定，
 * 无 starsector-core 子目录时按游戏根目录处理）。首次覆盖前把游戏目录原 jar 备份为
 * <name>.jar.obf-backup，备份已存在则跳过（不重复覆盖备份）；备份失败/游戏目录缺目标
 * jar/named 产物缺失均直接报错失败，不做任何兜底。
 *
 * 依赖 publishNamedGameJars 链路（其内已含 verifyNamedJarLinks 门禁），保证只有通过
 * 成员链接校验的 named jar 才允许进入游戏目录。安装是副作用操作，不做增量跳过：
 * 每次执行都重新做备份判定并覆盖安装。
 */
tasks.register("installNamedGameJars") {
    group = "mapping"
    description = "Install named game jars into the game directory (backup originals as *.jar.obf-backup first)"
    dependsOn("publishNamedGameJars")

    val gameDirPath = providers.gradleProperty("starsector.gameDir")
        .orNull?.takeIf { it.isNotBlank() }
    inputs.dir(namedGameJarsDir)
    // 安装目标是游戏目录（工作区外），只声明 named 产物为输入；不做输出增量判定。
    outputs.upToDateWhen { false }

    doLast {
        val targetRoot = gameDirPath?.let { resolveGameJarDirectory(it) }
            ?: throw GradleException("用法: ./gradlew :mapping:installNamedGameJars -Pstarsector.gameDir=<游戏目录>")
        require(targetRoot.isDirectory) {
            "游戏目录不存在: $targetRoot"
        }
        val namedJarRoot = namedGameJarsDir.get()
        gameJarBaseNames.forEach { baseName ->
            val targetJar = targetRoot.resolve("$baseName.jar")
            require(targetJar.isFile) {
                "游戏目录缺少目标 jar: $targetJar — 请确认游戏目录是完整 Starsector 安装（含 $baseName.jar）"
            }
            val backupFile = targetRoot.resolve("$baseName.jar.obf-backup")
            if (backupFile.isFile) {
                logger.lifecycle("[installNamedGameJars] 备份已存在，跳过备份: $backupFile")
            } else {
                targetJar.copyTo(backupFile)
                logger.lifecycle("[installNamedGameJars] 已备份原 jar -> $backupFile")
            }
            val namedJar = namedJarRoot.resolve("$baseName.jar")
            require(namedJar.isFile) {
                "named jar 产物缺失: $namedJar — 请先运行 :mapping:remapGameClasspathToNamed 或检查链接校验门禁"
            }
            namedJar.copyTo(targetJar, overwrite = true)
            logger.lifecycle("[installNamedGameJars] 已安装 named jar -> $targetJar")
        }
        logger.lifecycle("[installNamedGameJars] 安装完成。可用 NanoForge LaunchPrecheck（启动 precheck 的 named 判定门）复核安装结果。")
    }
}
