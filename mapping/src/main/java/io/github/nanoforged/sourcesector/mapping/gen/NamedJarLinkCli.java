package io.github.nanoforged.sourcesector.mapping.gen;

import io.github.nanoforged.sourcesector.mapping.MappingLookupException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * named 游戏 jar 成员链接校验命令行入口（Gradle {@code :mapping:verifyNamedJarLinks} 任务）。
 * <p>
 * 用法：{@code NamedJarLinkCli <namedJarDir> <reportFile>}
 * <p>
 * 以 {@code namedJarDir} 下 4 个游戏 jar（starfarer_obf / starfarer.api / fs.common_obf / fs.sound_obf）
 * 建立类成员索引，扫描全部字节码引用（方法调用 / 字段访问 / 常量池句柄），
 * 沿索引内继承链解析；无法解析（remap 跨类名字分叉）的引用写入 {@code reportFile}
 * 并按 owner 类聚类，存在断裂时以非零退出码失败（{@link MappingLookupException}）。
 * 第三方 jar 与 JDK 引用不在校验范围（remap 只发生在 4 个游戏 jar 内部）。
 */
public final class NamedJarLinkCli {
    /** 参与索引的 4 个游戏 jar 基名——与 mapping/build.gradle.kts 的 gameJarBaseNames 保持一致。 */
    private static final List<String> GAME_JAR_BASE_NAMES =
            List.of("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf");

    private NamedJarLinkCli() {
    }

    /**
     * 命令行入口。
     *
     * @param args 命令行参数
     * @throws Exception 若校验失败或发现断裂
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法: NamedJarLinkCli <namedJarDir> <reportFile>");
        }
        Path namedJarDir = Path.of(args[0]);
        Path reportFile = Path.of(args[1]);

        List<Path> gameJars = resolveGameJars(namedJarDir);
        NamedJarClassIndex index = NamedJarClassIndex.build(gameJars);
        List<NamedJarLinkValidator.Violation> violations =
                new NamedJarLinkValidator(index).validate(gameJars);

        List<String> report = NamedJarLinkValidator.renderReport(violations, gameJars);
        Files.createDirectories(reportFile.getParent());
        Files.write(reportFile, report, StandardCharsets.UTF_8);

        long ownerCount = violations.stream().map(NamedJarLinkValidator.Violation::targetOwner).distinct().count();
        long referencingClassCount = violations.stream()
                .map(NamedJarLinkValidator.Violation::referencingClass).distinct().count();
        System.out.println("[NamedJarLinkCli] 索引类 " + index.size()
                + " 个，断裂 " + violations.size() + " 条"
                + "（引用所在类 " + referencingClassCount + " 个 / owner 类 " + ownerCount + " 个），报告: " + reportFile);
        if (!violations.isEmpty()) {
            throw new MappingLookupException("named 游戏 jar 存在成员链接断裂（" + violations.size()
                    + " 条，引用所在类 " + referencingClassCount + " 个，owner 类 " + ownerCount
                    + " 个），详见报告: " + reportFile);
        }
    }

    /**
     * 从 named jar 目录解析 4 个游戏 jar（按基名精确匹配；第三方 jar 不参与索引）。
     *
     * @param namedJarDir named 游戏 jar 目录
     * @return 4 个游戏 jar 路径
     * @throws IOException 若目录不可读
     */
    private static List<Path> resolveGameJars(Path namedJarDir) throws IOException {
        List<Path> gameJars = new ArrayList<>();
        for (String baseName : GAME_JAR_BASE_NAMES) {
            Path jar = namedJarDir.resolve(baseName + ".jar");
            if (Files.isRegularFile(jar)) {
                gameJars.add(jar);
            }
        }
        if (gameJars.size() != GAME_JAR_BASE_NAMES.size()) {
            throw new IllegalArgumentException("named 游戏 jar 不完整（需要 "
                    + GAME_JAR_BASE_NAMES + "）: " + namedJarDir);
        }
        return gameJars;
    }
}
