package io.github.nanoforged.sourcesector.api;

import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MappingApi} 纯 Java API 测试：Gradle 构建脚本直接在 doLast 中
 * 以静态方法调用命令核心逻辑（不走 picocli），验证各方法返回值与异常约定。
 */
class MappingApiTest {

    @TempDir
    Path dir;

    @Test
    void generateProducesBothStagesAndStats() throws IOException {
        Path jar = jar(dir, "game.jar", "a/A", "x", "I");
        Path out = dir.resolve("m.tiny");

        MappingApi.GenerateResult r = MappingApi.generate(
                List.of(jar), List.of(), "com/fs", out, null);

        assertEquals(1, r.mappedClasses(), r.toString());
        assertTrue(r.mappedFields() >= 1, r.toString());
        assertTrue(Files.exists(out));
        // readableOutput 缺省派生。
        assertTrue(Files.exists(out.resolveSibling(out.getFileName() + ".readable")));
        MemoryMappingTree tree = MappingTreeUtil.read(out);
        assertEquals("obf", tree.getSrcNamespace());
        assertEquals("com/fs/class_0", tree.getClass("a/A").getDstName(0));
    }

    @Test
    void verifyReportsPairingState() throws IOException {
        Path stage1 = dir.resolve("s1.tiny");
        Files.writeString(stage1, """
                tiny\t2\t0\tobf\tintermediary
                c\ta/A\tcom/fs/class_0
                \tm\t()V\tm1\tmethod_0
                """, StandardCharsets.UTF_8);
        Path validStage2 = dir.resolve("s2-valid.tiny");
        Files.writeString(validStage2, """
                tiny\t2\t0\tintermediary\tnamed
                c\tcom/fs/class_0\tNamedA
                \tm\t()V\tmethod_0\trender
                """, StandardCharsets.UTF_8);
        Path brokenStage2 = dir.resolve("s2-broken.tiny");
        Files.writeString(brokenStage2, """
                tiny\t2\t0\tintermediary\tnamed
                c\tcom/fs/class_0\tNamedA
                \tm\t(Lcom/fs/missing;)V\tmethod_0\trender
                """, StandardCharsets.UTF_8);

        // 完整配对 passed；引用了悬空的中间名类则列出违规。
        assertTrue(MappingApi.verify(stage1, validStage2).passed());
        MappingApi.VerifyResult broken = MappingApi.verify(stage1, brokenStage2);
        assertFalse(broken.passed());
        assertTrue(broken.violations().stream()
                .anyMatch(v -> v.contains("com/fs/missing")), broken.violations().toString());
    }

    @Test
    void layermappingMergesAndOverrides() throws IOException {
        Path base = dir.resolve("base.tiny");
        Files.writeString(base, """
                tiny\t2\t0\tintermediary\tnamed
                c\tcom/fs/class_0\tA
                """, StandardCharsets.UTF_8);
        Path overlay = dir.resolve("overlay.tiny");
        Files.writeString(overlay, """
                tiny\t2\t0\tintermediary\tnamed
                c\tcom/fs/class_0\tB
                """, StandardCharsets.UTF_8);
        Path out = dir.resolve("merged.tiny");

        MappingApi.MergeResult r = MappingApi.layermapping(base, overlay, out);

        assertEquals(1, r.classes(), r.toString());
        MemoryMappingTree tree = MappingTreeUtil.read(out);
        // overlay 覆盖。
        assertEquals("B", tree.getClass("com/fs/class_0").getDstName(0));
    }

    @Test
    void enigmaConvertsFolder() throws IOException {
        Path maps = dir.resolve("maps");
        Files.createDirectories(maps);
        Files.writeString(maps.resolve("m.mapping"), """
                CLASS a/A NamedA
                \tMETHOD aa methodAa ()V
                """, StandardCharsets.UTF_8);
        Path out = dir.resolve("enigma.tiny");

        MappingApi.EnigmaResult r = MappingApi.enigma(maps, out);

        assertEquals(1, r.classes(), r.toString());
        assertTrue(r.members() >= 1, r.toString());
        MemoryMappingTree tree = MappingTreeUtil.read(out);
        assertEquals("NamedA", tree.getClass("a/A").getDstName(0));
    }

    @Test
    void missingAndInvalidArgumentsThrowIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> MappingApi.generate(List.of(), List.of(), null, dir.resolve("x.tiny"), null));
        assertThrows(IllegalArgumentException.class,
                () -> MappingApi.generate(List.of(), List.of(), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> MappingApi.layermapping(dir.resolve("a"), dir.resolve("b"), null));
        assertThrows(IllegalArgumentException.class,
                () -> MappingApi.enigma(dir.resolve("missing"), null));
        assertThrows(IllegalArgumentException.class,
                () -> MappingApi.generate(List.of(dir.resolve("x.jar")), List.of(), "bad;name",
                        dir.resolve("x.tiny"), null));
        assertThrows(IllegalArgumentException.class,
                () -> MappingApi.jarInputs(List.of(), List.of(dir.resolve("missing"))));
    }

    @Test
    void jarInputsScansSortedAndValidatesPrefix() throws IOException {
        Path dirs = dir.resolve("jars");
        Files.createDirectories(dirs);
        Files.write(dirs.resolve("b.jar"), new byte[0]);
        Files.write(dirs.resolve("a.jar"), new byte[0]);
        List<Path> found = MappingApi.jarInputs(List.of(), List.of(dirs));
        assertEquals(List.of(dirs.resolve("a.jar"), dirs.resolve("b.jar")), found);

        assertEquals("com/fs", MappingApi.normalizePrefix("com.fs"));
        assertEquals("com/x", MappingApi.normalizePrefix("/com/x/"));
    }

    // ---- 合成 jar 构建 ----

    private static Path jar(Path dir, String jarName, String className,
                            String fieldName, String fieldDesc) throws IOException {
        Path target = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new JarEntry(className + ".class"));
            out.write(bytecode(className, fieldName, fieldDesc));
            out.closeEntry();
        }
        return target;
    }

    private static byte[] bytecode(String name, String fieldName, String fieldDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, fieldName, fieldDesc, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}