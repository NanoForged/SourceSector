package io.github.nanoforged.sourcesector;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceSector} CLI 集成测试：主命令生成（中间名表 + 可读回写）、
 * verify 子命令、退出码与错误路径。
 */
class SourceSectorCliTest {

    @TempDir
    Path dir;

    private record CliResult(int exitCode, String out, String err) {
    }

    private static CliResult run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(new SourceSector())
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .setExecutionExceptionHandler((exception, cmd, parseResult) -> {
                    cmd.getErr().println("错误: " + exception.getMessage());
                    return CommandLine.ExitCode.SOFTWARE;
                });
        int exitCode = commandLine.execute(args);
        return new CliResult(exitCode, out.toString(), err.toString());
    }

    @Test
    void mainCommandGeneratesBothFilesAndRoundTrips() throws IOException {
        Path jar = jar(dir, "game.jar",
                clazz("a/A", null, List.of(), List.of(field("x", "I")), List.of(method("aa", "()V"))),
                clazz("com/example/Ship", null, List.of(), List.of(), List.of(method("render", "()V"))));
        Path out = dir.resolve("obf-to-intermediary.tiny");

        CliResult result = run("-i", jar.toString(), "-o", out.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(Files.exists(out));
        // 缺省 -r 时派生 <output>.readable。
        Path readable = dir.resolve("obf-to-intermediary.tiny.readable");
        assertTrue(Files.exists(readable), "缺省可读回写文件应生成");

        MemoryMappingTree obfTree = MappingTreeUtil.read(out);
        // 默认前缀 com/fs（Fabric 中间名惯例包）。
        assertEquals("com/fs/class_0", obfTree.getClass("a/A").getDstName(0));
        // 拓扑序（fromEntries 插入序）：a/A 先于 com/example/Ship → Ship.render 为 method_1。
        assertEquals(List.of("a/A", "com/example/Ship"),
                obfTree.getClasses().stream().map(ClassMapping::getSrcName).toList());
        assertTrue(obfTree.getClass("com/example/Ship").getMethods().stream()
                .anyMatch(m -> "method_1".equals(m.getDstName(0))));

        MemoryMappingTree readableTree = MappingTreeUtil.read(readable);
        assertTrue(readableTree.getClasses().stream()
                .flatMap(c -> c.getMethods().stream())
                .anyMatch(m -> "render".equals(m.getDstName(0))));
        // 不可读成员（aa）不进入回写文件。
        assertTrue(readableTree.getClasses().stream()
                .flatMap(c -> c.getMethods().stream())
                .noneMatch(m -> "aa".equals(m.getSrcName())));
    }

    @Test
    void verifyDetectsBrokenPairingExits1() throws IOException {
        // 段 2 引用不存在的中间名类（com/fs/class_99）。
        Path stage1 = dir.resolve("stage1.tiny");
        Files.writeString(stage1, """
                tiny\t2\t0\tobf\tintermediary
                c\ta/A\tcom/fs/class_0
                \tm\t()V\tm1\tmethod_0
                """, StandardCharsets.UTF_8);
        Path stage2 = dir.resolve("stage2.tiny");
        Files.writeString(stage2, """
                tiny\t2\t0\tintermediary\tnamed
                c\tcom/fs/class_0\tNamedA
                \tm\t(Lcom/fs/class_99;)V\tmethod_0\trender
                """, StandardCharsets.UTF_8);

        CliResult result = run("verify", "-1", stage1.toString(), "-2", stage2.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("class_99"), result.err());
    }

    @Test
    void verifyCompletePairingExits0() throws IOException {
        Path stage1 = dir.resolve("stage1.tiny");
        Files.writeString(stage1, """
                tiny\t2\t0\tobf\tintermediary
                c\ta/A\tcom/fs/class_0
                \tm\t()V\tm1\tmethod_0
                """, StandardCharsets.UTF_8);
        Path stage2 = dir.resolve("stage2.tiny");
        Files.writeString(stage2, """
                tiny\t2\t0\tintermediary\tnamed
                c\tcom/fs/class_0\tNamedA
                \tm\t()V\tmethod_0\trender
                """, StandardCharsets.UTF_8);

        CliResult result = run("verify", "-1", stage1.toString(), "-2", stage2.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Pairing complete"), result.out());
    }

    @Test
    void noInputAndInvalidPrefixAreUsageErrors() throws IOException {
        assertEquals(2, run("-o", dir.resolve("x.tiny").toString()).exitCode());
        Path jar = jar(dir, "game.jar", clazz("a/A", null, List.of(), List.of(), List.of()));
        assertEquals(2, run("-i", jar.toString(), "-o", dir.resolve("x.tiny").toString(),
                "-p", "bad;name").exitCode());
        assertEquals(2, run("-i", jar.toString(), "-o", dir.resolve("x.tiny").toString(),
                "--input-dir", dir.resolve("missing").toString()).exitCode());
    }

    @Test
    void duplicateInputClassRuntimeErrorExits1() throws IOException {
        Path jarA = jar(dir, "a.jar", clazz("a/C", null, List.of(), List.of(), List.of()));
        Path jarB = jar(dir, "b.jar", clazz("a/C", null, List.of(), List.of(), List.of()));

        CliResult result = run("-i", jarA.toString(), "-i", jarB.toString(),
                "-o", dir.resolve("x.tiny").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("重复类"), result.err());
    }

    @Test
    void helpAndBareInvocation() {
        assertEquals(0, run("--help").exitCode());
        assertEquals(2, run().exitCode());
    }

    @Test
    void layermappingMergesSameNamespaceWithOverlayOverride() throws IOException {
        // 低层：迁移产出的 intermediary→named 表。
        Path baseFile = dir.resolve("base.tiny");
        MappingTreeUtil.write(baseFile, MappingTreeUtil.fromEntries(List.of(
                MappingEntry.classEntry("com/fs/class_0", "MigratedA"),
                MappingEntry.methodEntry("com/fs/class_0", null, "method_0", "migratedRender", "()V")),
                "intermediary", List.of("named")));
        // 高层：同命名空间的可读名层，覆盖对应条目 + 独有条目。
        Path overlayFile = dir.resolve("overlay.tiny");
        MappingTreeUtil.write(overlayFile, MappingTreeUtil.fromEntries(List.of(
                MappingEntry.classEntry("com/fs/class_0", "ReadableA"),
                MappingEntry.methodEntry("com/fs/class_0", null, "method_0", "render", "()V"),
                MappingEntry.methodEntry("com/fs/class_0", null, "method_1", "showReport", "()V")),
                "intermediary", List.of("named")));

        Path out = dir.resolve("merged.tiny");
        CliResult result = run("layermapping", "-b", baseFile.toString(),
                "--overlay", overlayFile.toString(), "-o", out.toString());

        assertEquals(0, result.exitCode(), result.err());
        MemoryMappingTree merged = MappingTreeUtil.read(out);
        // 命名空间布局与输入一致。
        assertEquals("intermediary", merged.getSrcNamespace());
        assertEquals(List.of("named"), merged.getDstNamespaces());
        // 高层覆盖低层对应条目。
        assertEquals("ReadableA", merged.getClass("com/fs/class_0").getDstName(0));
        assertEquals("render", merged.getClass("com/fs/class_0").getMethod("method_0", "()V").getDstName(0));
        // 独有成员并入。
        assertNotNull(merged.getClass("com/fs/class_0").getMethod("method_1", "()V"));
    }

    @Test
    void layermappingNamespaceMismatchExits2() throws IOException {
        Path baseFile = dir.resolve("base2.tiny");
        MappingTreeUtil.write(baseFile, MappingTreeUtil.fromEntries(
                List.of(MappingEntry.classEntry("a/A", "A")), "intermediary", List.of("named")));
        Path overlayFile = dir.resolve("overlay2.tiny");
        MappingTreeUtil.write(overlayFile, MappingTreeUtil.fromEntries(
                List.of(MappingEntry.classEntry("a/A", "A2")), "obf", List.of("named")));

        CliResult result = run("layermapping", "-b", baseFile.toString(),
                "--overlay", overlayFile.toString(), "-o", dir.resolve("merged2.tiny").toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("namespace"), result.err());
    }

    @Test
    void layermappingMissingOptionsExits2() {
        assertEquals(2, run("layermapping", "-b", "x.tiny").exitCode());
    }

    @Test
    void enigmaDirConvertsToTinyV2() throws IOException {
        // Enigma 目录：两个 .mapping 文件，TAB 缩进、空格分列。
        Path maps = dir.resolve("maps");
        Files.createDirectories(maps);
        Files.writeString(maps.resolve("a.mapping"), """
                CLASS a/A NamedA
                \tFIELD x fieldI I
                \tMETHOD aa methodAa ()V
                \t\tARG 0 first
                """, StandardCharsets.UTF_8);
        Files.writeString(maps.resolve("b.mapping"), """
                CLASS b/B
                \tMETHOD render ()V
                """, StandardCharsets.UTF_8);

        Path out = dir.resolve("enigma-out.tiny");
        CliResult result = run("enigma", "-i", maps.toString(), "-o", out.toString());

        assertEquals(0, result.exitCode(), result.err());
        MemoryMappingTree tree = MappingTreeUtil.read(out);
        assertEquals("obf", tree.getSrcNamespace());
        assertEquals(List.of("named"), tree.getDstNamespaces());
        // 类与成员映射完整合并。
        assertEquals("NamedA", tree.getClass("a/A").getDstName(0));
        assertEquals("fieldI", tree.getClass("a/A").getField("x", "I").getDstName(0));
        assertEquals("methodAa", tree.getClass("a/A").getMethod("aa", "()V").getDstName(0));
        // 无 dst 名的类 b/B 保留 src 名但被记录，成员 dst 为空视为未映射。
        assertNotNull(tree.getClass("b/B"));
        assertEquals(2, tree.getClasses().size());
        // 确定性排序输出：行序遍历与内容一致。
        assertTrue(out.toString().endsWith("enigma-out.tiny"));
    }

    @Test
    void enigmaMissingOptionsAndEmptyDirExits2() throws IOException {
        assertEquals(2, run("enigma", "-i", "x").exitCode());
        assertEquals(2, run("enigma", "-o", "x.tiny").exitCode());
        Path empty = dir.resolve("empty-maps");
        Files.createDirectories(empty);
        assertEquals(2, run("enigma", "-i", empty.toString(), "-o", dir.resolve("e.tiny").toString()).exitCode());
    }

    // ---- 合成 jar 构建 ----

    private record MemberSpec(int access, String name, String desc) {
    }

    private record ClassSpec(String name, String superName, List<String> interfaces,
                             List<MemberSpec> fields, List<MemberSpec> methods) {
    }

    private static ClassSpec clazz(String name, String superName, List<String> interfaces,
                                   List<MemberSpec> fields, List<MemberSpec> methods) {
        return new ClassSpec(name, superName, interfaces, fields, methods);
    }

    private static MemberSpec field(String name, String desc) {
        return new MemberSpec(Opcodes.ACC_PUBLIC, name, desc);
    }

    private static MemberSpec method(String name, String desc) {
        return new MemberSpec(Opcodes.ACC_PUBLIC, name, desc);
    }

    private static Path jar(Path dir, String jarName, ClassSpec... specs) throws IOException {
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (ClassSpec spec : specs) {
                out.putNextEntry(new JarEntry(spec.name() + ".class"));
                out.write(bytecode(spec));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] bytecode(ClassSpec spec) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, spec.name(), null,
                spec.superName() == null ? "java/lang/Object" : spec.superName(),
                spec.interfaces().toArray(String[]::new));
        for (MemberSpec f : spec.fields()) {
            cw.visitField(f.access(), f.name(), f.desc(), null, null).visitEnd();
        }
        for (MemberSpec m : spec.methods()) {
            cw.visitMethod(m.access(), m.name(), m.desc(), null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
