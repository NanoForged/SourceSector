package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.nanoforged.sourcesector.mapping.core.TestJars.ClassSpec.clazz;
import static io.github.nanoforged.sourcesector.mapping.core.TestJars.ClassSpec.method;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MapperFacade} 集成测试：全管线确定性金样——
 * 双跑一致、输入参数顺序无关、zip 条目顺序无关、双文件 mapping-io 回读正确。
 */
class MapperFacadeDeterminismTest {

    @TempDir
    Path dir;

    private static List<TestJars.ClassSpec> scenario() {
        return List.of(
                clazz("a/A", null),
                TestJars.ClassSpec.withMembers("b/B", "a/A",
                        List.of(TestJars.ClassSpec.field("x", "I")),
                        List.of(method("aa", "()V"), method("update", "()V"))),
                clazz("i/I", null),
                TestJars.ClassSpec.withMembers("c/C", "b/B",
                        List.of(),
                        List.of(method("aa", "()V"), method("render", "()V"))),
                TestJars.ClassSpec.withMembers("com/example/Ship", "c/C",
                        List.of(TestJars.ClassSpec.field("speed", "F")),
                        List.of(method("render", "()V"), method("a", "()V"))));
    }

    @Test
    void doubleRunProducesIdenticalMappingsAndExportedBytes() throws IOException {
        Path jar = TestJars.jar(dir, "input.jar", scenario());

        MapperFacade facade = new MapperFacade();
        MapperFacade.MapperResult first = facade.generateMappings(List.of(jar), List.of(), null);
        MapperFacade.MapperResult second = facade.generateMappings(List.of(jar), List.of(), null);
        assertEquals(first.entries(), second.entries());

        Path out1 = dir.resolve("r1.tiny");
        Path out2 = dir.resolve("r2.tiny");
        MemoryMappingTree tree1 = MappingTreeUtil.fromEntries(first.entries(), "obf",
                List.of("intermediary", "named"));
        MemoryMappingTree tree2 = MappingTreeUtil.fromEntries(second.entries(), "obf",
                List.of("intermediary", "named"));
        MappingTreeUtil.writeProjection(out1, tree1, null, List.of("intermediary"), false);
        MappingTreeUtil.writeProjection(out2, tree2, null, List.of("intermediary"), false);
        assertArrayEquals(Files.readAllBytes(out1), Files.readAllBytes(out2));
    }

    @Test
    void inputJarArgumentOrderDoesNotAffectResult() throws IOException {
        Path jarA = TestJars.jar(dir, "a.jar", scenario().subList(0, 2));
        Path jarB = TestJars.jar(dir, "b.jar", scenario().subList(2, 5));

        MapperFacade facade = new MapperFacade();
        MapperFacade.MapperResult forward = facade.generateMappings(List.of(jarA, jarB), List.of(), null);
        MapperFacade.MapperResult reversed = facade.generateMappings(List.of(jarB, jarA), List.of(), null);

        assertEquals(forward.entries(), reversed.entries());
    }

    @Test
    void zipEntryOrderDoesNotAffectResult() throws IOException {
        Path normal = TestJars.jar(dir, "normal.jar", scenario());
        Path reversed = TestJars.jarReversed(dir, "reversed.jar", scenario());

        MapperFacade facade = new MapperFacade();
        MapperFacade.MapperResult fromNormal = facade.generateMappings(List.of(normal), List.of(), null);
        MapperFacade.MapperResult fromReversed = facade.generateMappings(List.of(reversed), List.of(), null);

        assertEquals(fromNormal.entries(), fromReversed.entries());
    }

    @Test
    void bothFilesRoundTripNamespacesAndContentCorrect() throws IOException {
        Path jar = TestJars.jar(dir, "input.jar", scenario());
        MapperFacade.MapperResult result = new MapperFacade().generateMappings(List.of(jar), List.of(), "com/out");

        Path obfFile = dir.resolve("obf.tiny");
        Path readableFile = dir.resolve("readable.tiny");
        MemoryMappingTree tree = MappingTreeUtil.fromEntries(result.entries(), "obf",
                List.of("intermediary", "named"));
        MappingTreeUtil.writeProjection(obfFile, tree, null, List.of("intermediary"), false);
        MappingTreeUtil.writeProjection(readableFile, tree, "intermediary", List.of("named"), true);

        MemoryMappingTree obfTree = MappingTreeUtil.read(obfFile);
        assertEquals("obf", obfTree.getSrcNamespace());
        assertEquals(List.of("intermediary"), obfTree.getDstNamespaces());
        assertNotNull(obfTree.getClass("com/example/Ship"));
        // 前缀生效：类目标名位于 com/out 包下。
        assertEquals("com/out/class_0", obfTree.getClass("a/A").getDstName(0));

        MemoryMappingTree readableTree = MappingTreeUtil.read(readableFile);
        assertEquals("intermediary", readableTree.getSrcNamespace());
        assertEquals(List.of("named"), readableTree.getDstNamespaces());
        // 可读回写：render 的两个声明（基类与子类）都应有可读名。
        assertNotNull(readableTree.getClass("com/out/class_2"));
        long renderCount = readableTree.getClasses().stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> "render".equals(m.getDstName(0)))
                .count();
        assertEquals(2, renderCount);
        // 不可读成员（a）不进入回写文件。
        long shortCount = readableTree.getClasses().stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> "a".equals(m.getSrcName()))
                .count();
        assertEquals(0, shortCount);
    }

    @Test
    void mappingStatsCorrect() throws IOException {
        Path jar = TestJars.jar(dir, "input.jar", scenario());
        MapperFacade.MapperResult result = new MapperFacade().generateMappings(List.of(jar), List.of(), null);

        // 5 个输入类；方法条目 = b/B(aa,update) + c/C(aa 复用,render) + Ship(render 复用,a) = 6；
        // 字段 = x + speed = 2。
        assertEquals(5, result.mappedClasses());
        assertEquals(6, result.mappedMethods());
        assertEquals(2, result.mappedFields());
        // 可读条目：类 Ship + update + render(c/C) + render(Ship) + speed = 5。
        assertEquals(5, result.readableCount());
        // 唯一中间方法名 = 4（method_0..3）：
        // 场景中各方法签名同为 ()V，但 name:desc 互不相同，连通分量按精确键归族——
        // aa(b/B)=method_0、update=method_1、render(c/C)=method_2、a(Ship)=method_3；
        // 跨代精确覆写（c/C.aa、Ship.render）仍收敛到祖先族。
        List<String> methods = result.entries().stream()
                .filter(MappingEntry::isMethod)
                .map(MappingEntry::intermediaryName)
                .toList();
        assertEquals(4, methods.stream().distinct().count());
    }
}
