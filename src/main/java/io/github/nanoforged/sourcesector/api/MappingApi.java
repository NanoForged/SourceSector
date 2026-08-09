package io.github.nanoforged.sourcesector.api;

import io.github.nanoforged.sourcesector.mapping.core.MapperFacade;
import io.github.nanoforged.sourcesector.mapping.core.MappingPairingValidator;
import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.format.enigma.EnigmaDirReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 无 UI 的纯 Java 门面：Gradle 构建脚本（或其他 Java 调用方）可在 doLast 中直接
 * 以静态方法调用全部命令的核心逻辑，不使用 picocli。异常约定：
 * <ul>
 *   <li>参数/状态不合法（缺参数、命名空间不一致、空 Enigma 目录等）抛
 *       {@link IllegalArgumentException}；</li>
 *   <li>IO 或解析失败抛 {@link IOException}（含 UncheckedIOException 包装）。</li>
 * </ul>
 * 各方法返回结构化结果 record，不向任何流打印。
 */
public final class MappingApi {

    private MappingApi() {
    }

    // ---- generate（主命令） ----

    /**
     * 从输入 jar 生成中间名映射并写出两个阶段文件：
     * {@code obf→intermediary}（table）+ {@code intermediary→readable}（back-write）。
     *
     * @param inputJars       输入混淆 jar（不可为空）
     * @param libraryJars     库 jar（仅继承分析，可为空）
     * @param prefix          中间名包前缀（点分自动转斜杠；空/空白=无前缀）
     * @param output          {@code obf→intermediary} 输出（Tiny v2，必填）
     * @param readableOutput  {@code intermediary→readable} 输出；{@code null} 时派生
     *                        {@code <output>.readable}
     * @return 生成结果（含各类统计与写出路径）
     * @throws IOException 读取 jar 或写出失败
     */
    public static GenerateResult generate(List<Path> inputJars,
                                          List<Path> libraryJars,
                                          String prefix,
                                          Path output,
                                          Path readableOutput) throws IOException {
        if (output == null) {
            throw new IllegalArgumentException("output is required");
        }
        List<Path> inputs = inputJars == null ? List.of() : List.copyOf(inputJars);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one input jar is required");
        }
        List<Path> libraries = libraryJars == null ? List.of() : List.copyOf(libraryJars);
        String normalizedPrefix = normalizePrefix(prefix);

        MapperFacade.MapperResult result = new MapperFacade()
                .generateMappings(inputs, libraries, normalizedPrefix);

        MemoryMappingTree tree = MappingTreeUtil.fromEntries(result.entries(), "obf",
                List.of("intermediary", "named"));
        MappingTreeUtil.writeProjection(output, tree, null, List.of("intermediary"), false);
        Path readable = readableOutput != null ? readableOutput
                : output.resolveSibling(output.getFileName() + ".readable");
        MappingTreeUtil.writeProjection(readable, tree, "intermediary", List.of("named"), true);

        return new GenerateResult(result.mappedClasses(), result.mappedMethods(), result.mappedFields(),
                result.readableCount(), output, readable);
    }

    // ---- verify ----

    /**
     * 校验两段映射可直接组合消费（零悬空类/成员/描述符引用、零重复类目标）。
     *
     * @param intermediary 中间名映射（obf→intermediary）
     * @param named       可读名映射（intermediary→named）
     * @return 校验结果（含违规列表）
     * @throws IOException 读取映射失败
     */
    public static VerifyResult verify(Path intermediary, Path named) throws IOException {
        if (intermediary == null || named == null) {
            throw new IllegalArgumentException("intermediary and named mappings are required");
        }
        MemoryMappingTree stage1 = MappingTreeUtil.read(intermediary);
        MemoryMappingTree stage2 = MappingTreeUtil.read(named);
        List<String> violations = MappingPairingValidator.validate(stage1, stage2);
        return new VerifyResult(violations.isEmpty(), violations);
    }

    // ---- layermapping ----

    /**
     * 合并两个命名空间布局一致的映射：overlay 覆盖 base 对应条目，
     * overlay 独有条目并入；base 被就地覆盖并写出到 {@code output}。
     *
     * @param base    低层映射（obf→intermediary 或 intermediary→named）
     * @param overlay 高层映射（同命名空间布局）
     * @param output  合并结果的写出路径（Tiny v2）
     * @return 合并结果
     * @throws IOException 读写映射失败
     */
    public static MergeResult layermapping(Path base, Path overlay, Path output) throws IOException {
        if (base == null || overlay == null || output == null) {
            throw new IllegalArgumentException("base, overlay and output are required");
        }
        MemoryMappingTree low = MappingTreeUtil.read(base);
        MemoryMappingTree high = MappingTreeUtil.read(overlay);
        if (!low.getSrcNamespace().equals(high.getSrcNamespace())
                || !low.getDstNamespaces().equals(high.getDstNamespaces())) {
            throw new IllegalArgumentException("namespace layout of the two mappings is inconsistent: "
                    + describe(low) + " vs " + describe(high));
        }

        MappingTreeUtil.mergeInto(low, high);
        MappingTreeUtil.write(output, low);

        long classes = low.getClasses().size();
        long members = countMembers(low);
        return new MergeResult(classes, members, output);
    }

    // ---- enigma ----

    /**
     * 将 Enigma 映射目录转成 Tiny v2（默认命名空间 {@code obf→named}）。
     *
     * @param input  Enigma 目录（递归扫描 {@code *.mapping}，需含至少一个映射）
     * @param output 输出（Tiny v2）
     * @return 转换结果
     * @throws IOException 读取或写出失败
     */
    public static EnigmaResult enigma(Path input, Path output) throws IOException {
        return enigma(input, output, "obf", "named");
    }

    /**
     * 将 Enigma 映射目录转成 Tiny v2，可用自定义命名空间名。
     *
     * @param input    Enigma 目录（递归扫描 {@code *.mapping}，需含至少一个映射）
     * @param output   输出（Tiny v2）
     * @param sourceNs 源命名空间名
     * @param targetNs 目标命名空间名
     * @return 转换结果
     * @throws IOException 读取或写出失败
     */
    public static EnigmaResult enigma(Path input, Path output, String sourceNs, String targetNs)
            throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output are required");
        }
        MemoryMappingTree tree = new MemoryMappingTree();
        EnigmaDirReader.read(input, sourceNs, targetNs, tree);
        if (tree.getClasses().isEmpty()) {
            throw new IllegalArgumentException("no mappings found under Enigma directory: " + input);
        }

        MappingTreeUtil.write(output, tree, VisitOrder.createByName());

        long classes = tree.getClasses().size();
        long members = countMembers(tree);
        return new EnigmaResult(classes, members, output);
    }

    // ---- 参数解析辅助（命令共用） ----

    /**
     * 汇总输入 jar：显式列表 + 目录扫描（目录内 {@code *.jar} 按路径排序并入）。
     * 汇总结果为空时抛 {@link IllegalArgumentException}。
     *
     * @param jars 显式 jar 列表（可为空）
     * @param dirs 目录列表（可为空）
     * @return 汇总后的 jar 列表
     * @throws IOException 扫描失败
     */
    public static List<Path> jarInputs(List<Path> jars, List<Path> dirs) throws IOException {
        List<Path> all = jarLibraries(jars, dirs);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("no input jars specified (pass jars or jar dirs)");
        }
        return all;
    }

    /**
     * 汇总库 jar（可空）：显式列表 + 目录扫描（排序）。
     *
     * @param jars 显式 jar 列表（可为空）
     * @param dirs 目录列表（可为空）
     * @return 汇总后的 jar 列表（可能为空）
     * @throws IOException 扫描失败
     */
    public static List<Path> jarLibraries(List<Path> jars, List<Path> dirs) throws IOException {
        List<Path> all = new ArrayList<>(jars == null ? List.of() : jars);
        if (dirs != null) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    throw new IllegalArgumentException("directory does not exist: " + dir);
                }
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                            .sorted()
                            .forEach(all::add);
                }
            }
        }
        return all;
    }

    /**
     * 校验并归一化中间名包前缀（点分自动转内部名斜杠形式）。
     *
     * @param prefix 前缀（可为空）
     * @return 归一化前缀；空/空白返回 {@code null}
     * @throws IllegalArgumentException 前缀非法
     */
    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        String normalized = prefix.replace('.', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("prefix cannot be empty");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.indexOf(';') >= 0 || segment.indexOf('[') >= 0
                    || segment.indexOf('(') >= 0) {
                throw new IllegalArgumentException(
                        "invalid prefix (expected a package path like com/example/out): " + prefix);
            }
        }
        return normalized;
    }

    private static long countMembers(MemoryMappingTree tree) {
        return tree.getClasses().stream().flatMap(c -> c.getFields().stream()).count()
                + tree.getClasses().stream().flatMap(c -> c.getMethods().stream()).count();
    }

    private static String describe(MemoryMappingTree tree) {
        return tree.getSrcNamespace() + "→" + String.join("+", tree.getDstNamespaces());
    }

    /**
     * 生成结果。
     *
     * @param mappedClasses  映射类数
     * @param mappedMethods  映射方法数
     * @param mappedFields   映射字段数
     * @param readableCount  携带可读名回写的条目数
     * @param output         obf→intermediary 输出路径
     * @param readableOutput intermediary→readable 输出路径
     */
    public record GenerateResult(int mappedClasses,
                                 int mappedMethods,
                                 int mappedFields,
                                 int readableCount,
                                 Path output,
                                 Path readableOutput) {
    }

    /**
     * 校验结果。
     *
     * @param passed    是否完全配对（无违规）
     * @param violations 违规描述列表（passed 时为空）
     */
    public record VerifyResult(boolean passed, List<String> violations) {
    }

    /**
     * 合并结果。
     *
     * @param classes 合并后类数
     * @param members 合并后成员数
     * @param output  写出路径
     */
    public record MergeResult(long classes, long members, Path output) {
    }

    /**
     * Enigma 转换结果。
     *
     * @param classes 类数
     * @param members 成员数
     * @param output  写出路径
     */
    public record EnigmaResult(long classes, long members, Path output) {
    }
}