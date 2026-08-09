package io.github.nanoforged.sourcesector.mapping.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.nanoforged.sourcesector.mapping.core.TestJars.ClassSpec.clazz;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassProvider} 测试：多 jar 合并排序、重复策略、排除规则与 zip 顺序无关性。
 */
class ClassProviderTest {

    @TempDir
    Path dir;

    @Test
    void multipleInputJarsMergedSortedByInternalName() throws IOException {
        Path jarA = TestJars.jar(dir, "a.jar", clazz("z/Last", null), clazz("a/First", null));
        Path jarB = TestJars.jar(dir, "b.jar", clazz("m/Mid", null));

        // 故意逆序传参：结果必须与参数顺序无关。
        ClassSet set = ClassProvider.load(List.of(jarB, jarA), List.of());

        assertEquals(List.of("a/First", "m/Mid", "z/Last"), new ArrayList<>(set.inputs().keySet()));
        assertTrue(set.libraries().isEmpty());
    }

    @Test
    void duplicateClassAcrossInputJarsThrows() throws IOException {
        Path jarA = TestJars.jar(dir, "a.jar", clazz("a/C", null));
        Path jarB = TestJars.jar(dir, "b.jar", clazz("a/C", null));

        SourceSectorException e = assertThrows(SourceSectorException.class,
                () -> ClassProvider.load(List.of(jarA, jarB), List.of()));
        assertTrue(e.getMessage().contains("a/C"), e.getMessage());
    }

    @Test
    void inputsAndLibrariesKeptSeparately() throws IOException {
        Path input = TestJars.jar(dir, "in.jar", clazz("a/C", null));
        Path library = TestJars.jar(dir, "lib.jar", clazz("a/C", null));

        ClassSet set = ClassProvider.load(List.of(input), List.of(library));

        assertTrue(set.inputs().containsKey("a/C"));
        assertTrue(set.libraries().containsKey("a/C"));
    }

    @Test
    void duplicateLibrariesFirstWinsByJarPathOrder() throws IOException {
        // libA 路径字典序在前，其结构（方法 foo）胜出。
        Path libA = TestJars.jar(dir, "a-lib.jar",
                TestJars.ClassSpec.withMembers("a/C", null, List.of(), List.of(TestJars.ClassSpec.method("foo", "()V"))));
        Path libB = TestJars.jar(dir, "b-lib.jar",
                TestJars.ClassSpec.withMembers("a/C", null, List.of(), List.of(TestJars.ClassSpec.method("bar", "()V"))));

        ClassSet set = ClassProvider.load(List.of(), List.of(libB, libA));

        ClassStructure structure = set.libraries().get("a/C");
        assertEquals(1, structure.methods().size());
        assertEquals("foo", structure.methods().getFirst().name());
    }

    @Test
    void moduleInfoMultiReleaseAndObjectExcluded() throws IOException {
        Path jar = TestJars.jarWithExtras(dir, "extras.jar", clazz("a/C", null));

        ClassSet set = ClassProvider.load(List.of(jar), List.of());

        assertEquals(List.of("a/C"), new ArrayList<>(set.inputs().keySet()));
    }

    @Test
    void zipEntryOrderDoesNotAffectResult() throws IOException {
        List<TestJars.ClassSpec> specs = List.of(clazz("a/A", null), clazz("b/B", null), clazz("c/C", null));
        Path normal = TestJars.jar(dir, "normal.jar", specs);
        Path reversed = TestJars.jarReversed(dir, "reversed.jar", specs);

        ClassSet normalSet = ClassProvider.load(List.of(normal), List.of());
        ClassSet reversedSet = ClassProvider.load(List.of(reversed), List.of());

        assertEquals(normalSet.inputs(), reversedSet.inputs());
    }
}
