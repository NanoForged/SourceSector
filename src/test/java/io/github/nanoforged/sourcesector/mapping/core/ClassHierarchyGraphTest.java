package io.github.nanoforged.sourcesector.mapping.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassHierarchyBuilder}/{@link ClassHierarchyGraph} 测试：
 * 拓扑序确定性、phantom 补桩、环检测与祖先查询。
 */
class ClassHierarchyGraphTest {

    @Test
    void topologicalOrderParentsBeforeChildrenObjectFirst() {
        ClassHierarchyGraph graph = graphOf(
                cs("a/A", null),
                cs("b/B", "a/A"),
                cs("c/C", "b/B"));

        List<String> order = graph.topologicalOrder();

        assertEquals("java/lang/Object", order.getFirst());
        assertTrue(order.indexOf("a/A") < order.indexOf("b/B"), order.toString());
        assertTrue(order.indexOf("b/B") < order.indexOf("c/C"), order.toString());
    }

    @Test
    void readyQueuePopsByInternalNameLexicographicOrder() {
        ClassHierarchyGraph graph = graphOf(
                cs("a/A", null),
                cs("b/B2", "a/A"),
                cs("b/B1", "a/A"),
                cs("b/B10", "a/A"));

        List<String> order = graph.topologicalOrder();

        // B1 < B10 < B2（字典序：'1' < '2'）
        int b1 = order.indexOf("b/B1");
        int b10 = order.indexOf("b/B10");
        int b2 = order.indexOf("b/B2");
        assertTrue(b1 < b10 && b10 < b2, order.toString());
    }

    @Test
    void missingSuperclassCreatesPhantomStub() {
        ClassHierarchyGraph graph = graphOf(cs("x/X", "missing/Missing"));

        assertTrue(graph.contains("missing/Missing"));
        assertFalse(graph.isMapped("missing/Missing"));
        assertNull(graph.structureOf("missing/Missing"));
        assertTrue(graph.topologicalOrder().indexOf("missing/Missing") < graph.topologicalOrder().indexOf("x/X"));
    }

    @Test
    void libraryClassesInGraphButNotMapped() {
        ClassStructure library = cs("lib/L", null);
        ClassStructure input = cs("x/X", "lib/L");
        ClassHierarchyGraph graph = ClassHierarchyBuilder.build(
                new ClassSet(single(input), single(library)));

        assertTrue(graph.contains("lib/L"));
        assertFalse(graph.isMapped("lib/L"));
        assertTrue(graph.isMapped("x/X"));
    }

    @Test
    void inputStructureTakesPrecedenceOverLibrary() {
        ClassStructure input = cs("a/C", "java/lang/Object");
        ClassStructure library = cs("a/C", "lib/L");
        ClassHierarchyGraph graph = ClassHierarchyBuilder.build(
                new ClassSet(single(input), single(library)));

        assertEquals("java/lang/Object", graph.structureOf("a/C").superName());
    }

    @Test
    void cycleDetectionThrows() {
        ClassHierarchyGraph graph = graphOf(
                cs("a/A", "b/B"),
                cs("b/B", "a/A"));

        SourceSectorException e = assertThrows(SourceSectorException.class, graph::topologicalOrder);
        assertTrue(e.getMessage().contains("a/A") || e.getMessage().contains("b/B"), e.getMessage());
    }

    @Test
    void ancestorsIncludeSuperAndInterfacesDeduped() {
        ClassHierarchyGraph graph = graphOf(
                cs("i/I1", null),
                cs("i/I2", null),
                cs("b/B", "java/lang/Object", "i/I1"),
                cs("c/C", "b/B", "i/I2", "i/I1"));

        List<String> ancestors = graph.ancestorsOf("c/C");

        // BFS：父类在前、接口按声明顺序；I1 经 B 与 C 双路径引用只出现一次。
        assertEquals(List.of("b/B", "i/I2", "i/I1", "java/lang/Object"), ancestors);
    }

    @Test
    void superChainFollowsSingleSuperclassLineage() {
        ClassHierarchyGraph graph = graphOf(
                cs("a/A", null),
                cs("b/B", "a/A"),
                cs("c/C", "b/B"));

        assertEquals(List.of("b/B", "a/A"), graph.superChainOf("c/C"));
        assertEquals(List.of("a/A"), graph.superChainOf("b/B"));
        assertEquals(List.of(), graph.superChainOf("a/A"));
    }

    @Test
    void interfaceClosureIncludesTransitiveParentsInDeclarationOrder() {
        ClassHierarchyGraph graph = graphOf(
                cs("i/I0", null),
                cs("i/I1", null, "i/I0"),
                cs("i/I2", null),
                cs("b/B", null),
                cs("c/C", "b/B", "i/I1", "i/I2"));

        // 声明序：I1（及其父 I0）先于 I2。
        assertEquals(List.of("i/I1", "i/I0", "i/I2"), graph.interfaceClosureOf("c/C"));
        assertEquals(List.of("i/I0"), graph.interfaceClosureOf("i/I1"));
        assertEquals(List.of(), graph.interfaceClosureOf("i/I2"));
    }

    private static ClassHierarchyGraph graphOf(ClassStructure... classes) {
        SortedMap<String, ClassStructure> inputs = new TreeMap<>();
        for (ClassStructure structure : classes) {
            inputs.put(structure.name(), structure);
        }
        return ClassHierarchyBuilder.build(new ClassSet(inputs, new TreeMap<>()));
    }

    private static SortedMap<String, ClassStructure> single(ClassStructure structure) {
        SortedMap<String, ClassStructure> map = new TreeMap<>();
        map.put(structure.name(), structure);
        return map;
    }

    private static ClassStructure cs(String name, String superName, String... interfaces) {
        // 测试桩与真实类文件一致：非 Object 类必须显式声明父类。
        String resolvedSuper = superName == null ? "java/lang/Object" : superName;
        return new ClassStructure(name, resolvedSuper, List.of(interfaces), List.of(), List.of());
    }
}
