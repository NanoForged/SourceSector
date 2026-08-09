package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;
import net.fabricmc.mappingio.tree.MappingTree.FieldMapping;
import net.fabricmc.mappingio.tree.MappingTree.MethodMapping;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MappingTreeUtil} 测试：Tiny v2 行布局（desc 列前置、escaped-names 属性）、
 * 投影链（MappingSourceNsSwitch 切 src + desc 换算、EmptyElementFilter 滤空）、
 * addClass 分层合并语义、插入序保持与确定性。
 */
class MappingTreeUtilTest {

    @TempDir
    Path dir;

    private static List<MappingEntry> sampleEntries() {
        return List.of(
                MappingEntry.classEntry("a/A", "class_0", null),
                MappingEntry.methodEntry("a/A", "class_0", "foo", "method_0", null, "()V"),
                MappingEntry.fieldEntry("a/A", "class_0", "x", "field_0", null, "I"),
                MappingEntry.classEntry("com/example/Ship", "class_1", "com/example/Ship"),
                MappingEntry.methodEntry("com/example/Ship", "class_1", "render", "method_1", "render", "()V"));
    }

    private static MemoryMappingTree buildTree() {
        return MappingTreeUtil.fromEntries(sampleEntries(), "obf", List.of("intermediary", "named"));
    }

    @Test
    void file1LineLayoutConformsToTinyV2() throws IOException {
        Path out = dir.resolve("obf-to-intermediary.tiny");
        MappingTreeUtil.writeProjection(out, buildTree(), null, List.of("intermediary"), false);

        List<String> lines = Files.readAllLines(out);
        assertEquals("tiny\t2\t0\tobf\tintermediary", lines.get(0));
        assertEquals("\tescaped-names", lines.get(1));
        assertTrue(lines.contains("c\ta/A\tclass_0"), lines.toString());
        // 成员行：描述符为第二列（规范列序，legacy 方言为名字在前）。
        assertTrue(lines.contains("\tm\t()V\tfoo\tmethod_0"), lines.toString());
        assertTrue(lines.contains("\tf\tI\tx\tfield_0"), lines.toString());
        assertTrue(lines.contains("c\tcom/example/Ship\tclass_1"), lines.toString());
    }

    @Test
    void file1MappingIoRoundTripConsistent() throws IOException {
        Path out = dir.resolve("obf-to-intermediary.tiny");
        MappingTreeUtil.writeProjection(out, buildTree(), null, List.of("intermediary"), false);

        MemoryMappingTree tree = MappingTreeUtil.read(out);

        assertEquals("obf", tree.getSrcNamespace());
        assertEquals(List.of("intermediary"), tree.getDstNamespaces());
        ClassMapping cls = tree.getClass("a/A");
        assertEquals("class_0", cls.getDstName(0));
        MethodMapping method = cls.getMethods().stream()
                .filter(m -> "foo".equals(m.getSrcName()) && "()V".equals(m.getSrcDesc()))
                .findFirst().orElseThrow();
        assertEquals("method_0", method.getDstName(0));
    }

    @Test
    void file2ReadableOnlyWithEmptyClassTargetsRetained() throws IOException {
        List<MappingEntry> entries = List.of(
                MappingEntry.classEntry("b/B", "class_0", null),
                MappingEntry.methodEntry("b/B", "class_0", "update", "method_0", "update", "()V"),
                MappingEntry.classEntry("c/C", "class_1", null),
                MappingEntry.methodEntry("c/C", "class_1", "a", "method_1", null, "()V"),
                MappingEntry.classEntry("com/example/Ship", "class_2", "com/example/Ship"));
        MemoryMappingTree tree = MappingTreeUtil.fromEntries(entries, "obf", List.of("intermediary", "named"));

        Path out = dir.resolve("intermediary-to-readable.tiny");
        MappingTreeUtil.writeProjection(out, tree, "intermediary", List.of("named"), true);

        List<String> lines = Files.readAllLines(out);
        assertEquals("tiny\t2\t0\tintermediary\tnamed", lines.get(0));
        // b/B 类本身不可读但成员可读：类行保留、目标名为空列。
        assertTrue(lines.contains("c\tclass_0\t"), lines.toString());
        assertTrue(lines.contains("\tm\t()V\tmethod_0\tupdate"), lines.toString());
        // c/C 类与成员均不可读：整行省略。
        assertTrue(lines.stream().noneMatch(line -> line.contains("class_1")), lines.toString());
        // 可读类整行输出。
        assertTrue(lines.contains("c\tclass_2\tcom/example/Ship"), lines.toString());

        MemoryMappingTree tree2 = MappingTreeUtil.read(out);
        assertNull(tree2.getClass("class_0").getDstName(0));
        assertEquals("com/example/Ship", tree2.getClass("class_2").getDstName(0));
    }

    @Test
    void stage2DescriptorClassNameConvertedToIntermediary() throws IOException {
        // Tiny v2 语义：段 2 的 src 命名空间是 intermediary，成员描述符属于 src 侧——
        // MappingSourceNsSwitch 输出侧把 desc 中的 obf 类名换算为 class_N。
        List<MappingEntry> entries = List.of(
                MappingEntry.classEntry("a/A", "class_0", "NamedA"),
                MappingEntry.methodEntry("a/A", "class_0", "m1", "method_0",
                        "showAccidentReport", "(La/A;)V"),
                MappingEntry.classEntry("lib/L", "class_1", "NamedL"),
                MappingEntry.methodEntry("lib/L", "class_1", "m2", "method_1", "m2Named",
                        "(Ljava/util/List;)V"));
        MemoryMappingTree tree = MappingTreeUtil.fromEntries(entries, "obf", List.of("intermediary", "named"));

        Path out = dir.resolve("stage2.tiny");
        MappingTreeUtil.writeProjection(out, tree, "intermediary", List.of("named"), true);

        List<String> lines = Files.readAllLines(out);
        assertTrue(lines.contains("\tm\t(Lclass_0;)V\tmethod_0\tshowAccidentReport"), lines.toString());
        // 未映射的库类引用保持原样。
        assertTrue(lines.contains("\tm\t(Ljava/util/List;)V\tmethod_1\tm2Named"), lines.toString());
    }

    @Test
    void writeTwiceBytesIdentical() throws IOException {
        Path first = dir.resolve("first.tiny");
        Path second = dir.resolve("second.tiny");
        MappingTreeUtil.writeProjection(first, buildTree(), null, List.of("intermediary"), false);
        MappingTreeUtil.writeProjection(second, buildTree(), null, List.of("intermediary"), false);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void fromEntriesPreservesInsertionOrder() {
        MemoryMappingTree tree = buildTree();

        List<String> classNames = tree.getClasses().stream()
                .map(ClassMapping::getSrcName).toList();
        // 生成器拓扑序原样保留（类序与条目声明序一致）。
        assertEquals(List.of("a/A", "com/example/Ship"), classNames);
    }

    @Test
    void mergeIntoOverridesLowLayerAndMergesUniqueEntries() throws IOException {
        MemoryMappingTree base = MappingTreeUtil.fromEntries(List.of(
                MappingEntry.classEntry("a/A", "class_0", null),
                MappingEntry.methodEntry("a/A", "class_0", "m", "method_0", "mA1", "()V"),
                MappingEntry.classEntry("b/B", "class_1", null),
                MappingEntry.methodEntry("b/B", "class_1", "m", "method_1", "mB1", "()V")),
                "obf", List.of("intermediary", "named"));
        MemoryMappingTree overlay = MappingTreeUtil.fromEntries(List.of(
                MappingEntry.classEntry("a/A", "class_0", null),
                MappingEntry.methodEntry("a/A", "class_0", "m", "method_0", "mA2", "()V"),
                MappingEntry.methodEntry("a/A", "class_0", "m2", "method_2", "mOnly", "()V"),
                MappingEntry.classEntry("c/C", "class_2", "NamedC"),
                MappingEntry.methodEntry("c/C", "class_2", "m3", "method_3", "mC", "()V")),
                "obf", List.of("intermediary", "named"));

        MappingTreeUtil.mergeInto(base, overlay);

        // 类被覆盖/独有类并入。
        assertEquals(3, base.getClasses().size());
        assertEquals("NamedC", base.getClass("c/C").getDstName(1));
        // 成员被覆盖：mA2；独有成员并入（挂在 base 已有类下）。
        MethodMapping m = base.getClass("a/A").getMethods().stream()
                .filter(mm -> "method_0".equals(mm.getDstName(0))).findFirst().orElseThrow();
        assertEquals("mA2", m.getDstName(1));
        assertNotNull(base.getClass("a/A").getMethod("m2", "()V"));
        // 未覆盖成员保持 base。
        assertEquals("mB1", base.getClass("b/B").getMethods().iterator().next().getDstName(1));
        // 独有类成员并入。
        assertNotNull(base.getClass("c/C").getMethod("m3", "()V"));
        // overlay 独有类按插入序追加到末尾。
        List<String> classNames = base.getClasses().stream().map(ClassMapping::getSrcName).toList();
        assertEquals(List.of("a/A", "b/B", "c/C"), classNames);
    }

    @Test
    void unnamedHighLayerEntryDoesNotOverride() throws IOException {
        MemoryMappingTree base = MappingTreeUtil.fromEntries(List.of(
                MappingEntry.classEntry("a/A", "class_0", "NamedA"),
                MappingEntry.fieldEntry("a/A", "class_0", "f", "field_0", "fA1", "I")),
                "obf", List.of("intermediary", "named"));
        MemoryMappingTree overlay = MappingTreeUtil.fromEntries(List.of(
                MappingEntry.classEntry("a/A", "class_0", null),
                MappingEntry.fieldEntry("a/A", "class_0", "f", "field_0", null, "I")),
                "obf", List.of("intermediary", "named"));

        MappingTreeUtil.mergeInto(base, overlay);

        assertEquals("NamedA", base.getClass("a/A").getDstName(1), "未命名类不覆盖");
        FieldMapping field = base.getClass("a/A").getFields().iterator().next();
        assertEquals("fA1", field.getDstName(1), "未命名成员不覆盖");
    }
}
