package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.mapping.core.ClassStructure;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MappingGenerator} 测试：顺序编号、覆盖复用、菱形裁决、前缀与可读名回写。
 */
class MappingGeneratorTest {

    @Test
    void sequenceNumbersIssuedByTopologicalAndDeclarationOrder() {
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null,
                        List.of(field("x", "I"), field("y", "J")),
                        List.of(method("foo", "()V"), method("bar", "(I)V"))));

        assertEquals("class_0", classEntry(entries, "a/A").intermediaryName());
        assertEquals("field_0", memberEntry(entries, "a/A", "x").intermediaryName());
        assertEquals("field_1", memberEntry(entries, "a/A", "y").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "a/A", "foo").intermediaryName());
        assertEquals("method_1", memberEntry(entries, "a/A", "bar").intermediaryName());
    }

    @Test
    void overridingMethodReusesAncestorIntermediaryName() {
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(), List.of(method("foo", "()V"))),
                cs("b/B", "a/A", List.of(), List.of(method("foo", "()V"), method("baz", "()V"))));

        assertEquals("method_0", memberEntry(entries, "a/A", "foo").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "b/B", "foo").intermediaryName());
        // b/B 类内 ()V 出现两次（foo+baz），baz 是独立方法（不同 name:desc），独立编号。
        assertEquals("method_1", memberEntry(entries, "b/B", "baz").intermediaryName());
    }

    @Test
    void interfaceMethodReusedByImplementingClass() {
        List<MappingEntry> entries = generate(prefix(null),
                csWithIf("i/I", null, List.of(), List.of(method("bar", "()V"))),
                csWithIf("c/C", "java/lang/Object", List.of("i/I"), List.of(method("bar", "()V"))));

        assertEquals("method_0", memberEntry(entries, "i/I", "bar").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "c/C", "bar").intermediaryName());
    }

    @Test
    void diamondInterfacesCollapseIntoConnectedFamily() {
        List<MappingEntry> entries = generate(prefix(null),
                csWithIf("i/I1", null, List.of(), List.of(method("m", "()V"))),
                csWithIf("i/I2", null, List.of(), List.of(method("m", "()V"))),
                csWithIf("c/C", "java/lang/Object", List.of("i/I1", "i/I2"), List.of(method("m", "()V"))));

        // 连通分量语义：c/C 的祖先闭包同时含 I1、I2，三个声明者（I1、I2、C）
        // 经 C 互达 → 归为同一方法族，全部收敛 method_0（接口族塌缩，对齐 Fabric）。
        assertEquals("method_0", memberEntry(entries, "i/I1", "m").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "i/I2", "m").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "c/C", "m").intermediaryName());
    }

    @Test
    void superAndInterfaceSameSignatureCollapseIntoConnectedFamily() {
        // 连通分量语义：c/C 祖先闭包同时含 super s/S 与接口 i/I，三者声明 foo:()V
        // 且经 C 互达 → 归为同一方法族，全部收敛为 method_0（不再有超类优先裁决）。
        List<MappingEntry> entries = generate(prefix(null),
                csWithIf("i/I", null, List.of(), List.of(method("foo", "()V"))),
                cs("s/S", null, List.of(), List.of(method("foo", "()V"))),
                csWithIf("c/C", "s/S", List.of("i/I"), List.of(method("foo", "()V"))));

        assertEquals("method_0", memberEntry(entries, "i/I", "foo").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "s/S", "foo").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "c/C", "foo").intermediaryName());
    }

    @Test
    void renamedVirtualMethodStaysIndependentByDescriptor() {
        // 完全对齐 Fabric：删 desc 归并后，不同混淆名（bar vs foo）即使同签名 ()V
        // 也不再收敛，各自独立编号（a/A.foo=method_0、b/B.bar=method_1）。
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(), List.of(method("foo", "()V"))),
                cs("b/B", "a/A", List.of(), List.of(method("bar", "()V"))));

        assertEquals("method_0", memberEntry(entries, "a/A", "foo").intermediaryName());
        assertEquals("method_1", memberEntry(entries, "b/B", "bar").intermediaryName());
    }

    @Test
    void unrelatedSameDescriptorStaysIndependent() {
        // 两个无关类声明相同签名 ()V 的方法，无共同祖先：
        // 签名族归并仅限祖先链内，不得跨树合并，各自独立编号。
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(), List.of(method("foo", "()V"))),
                cs("b/B", null, List.of(), List.of(method("qux", "()V"))));

        assertEquals("method_0", memberEntry(entries, "a/A", "foo").intermediaryName());
        assertEquals("method_1", memberEntry(entries, "b/B", "qux").intermediaryName());
    }

    @Test
    void interfaceImplementersDeclaringSameKeyCollapseIntoFamily() {
        // 接口族塌缩：i/I 声明 run()V，a/A、b/B 各自实现 i/I 且声明同名同签名方法。
        // 三者经接口闭包互达 → 归为同一方法族，全部共享 method_0（对齐 Fabric，Q4 语义）。
        List<MappingEntry> entries = generate(prefix(null),
                csWithIf("i/I", null, List.of(), List.of(method("run", "()V"))),
                csWithIf("a/A", "java/lang/Object", List.of("i/I"), List.of(method("run", "()V"))),
                csWithIf("b/B", "java/lang/Object", List.of("i/I"), List.of(method("run", "()V"))));

        assertEquals("method_0", memberEntry(entries, "i/I", "run").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "a/A", "run").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "b/B", "run").intermediaryName());
    }

    @Test
    void privateAndStaticMethodsStayIndependent() {
        // 私有/静态方法不参与族归并：c/C 私有 hide()V 与静态 stat()V 不吸附祖先族。
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(), List.of(method("foo", "()V"))),
                cs("c/C", "a/A", List.of(),
                        List.of(new ClassStructure.Member("hide", "()V", Opcodes.ACC_PRIVATE),
                                new ClassStructure.Member("stat", "()V", Opcodes.ACC_STATIC))));

        assertEquals("method_0", memberEntry(entries, "a/A", "foo").intermediaryName());
        assertEquals("method_1", memberEntry(entries, "c/C", "hide").intermediaryName());
        assertEquals("method_2", memberEntry(entries, "c/C", "stat").intermediaryName());
    }

    @Test
    void constructorsAndStaticInitializerProduceNoMappings() {
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(),
                        List.of(method("<init>", "()V"), method("<clinit>", "()V"), method("foo", "()V"))));

        assertEquals(2, entries.size()); // 类条目 + foo
        assertTrue(entries.stream().noneMatch(e -> "<init>".equals(e.obfuscatedName())));
        assertTrue(entries.stream().noneMatch(e -> "<clinit>".equals(e.obfuscatedName())));
    }

    @Test
    void prefixPackageAppliedToClassName() {
        List<MappingEntry> entries = generate(prefix("com.example.out"),
                cs("a/A", null, List.of(), List.of(method("foo", "()V"))));

        MappingEntry classEntry = classEntry(entries, "a/A");
        assertEquals("com/example/out/class_0", classEntry.intermediaryName());
        // 成员 owner 目标侧名跟随类中间名。
        assertEquals("com/example/out/class_0", memberEntry(entries, "a/A", "foo").ownerNamedName());
    }

    @Test
    void readableBackfillCarriesOriginalName() {
        List<MappingEntry> entries = generate(prefix(null),
                cs("com/example/Ship", null, List.of(),
                        List.of(method("render", "()V"), method("a", "()V"), field("speed", "I"), field("x", "I"))));

        assertEquals("com/example/Ship", classEntry(entries, "com/example/Ship").namedName());
        assertEquals("render", memberEntry(entries, "com/example/Ship", "render").namedName());
        assertNull(memberEntry(entries, "com/example/Ship", "a").namedName());
        assertEquals("speed", memberEntry(entries, "com/example/Ship", "speed").namedName());
        assertNull(memberEntry(entries, "com/example/Ship", "x").namedName());
    }

    @Test
    void fieldsNumberedPerClassNoReuseAcrossClasses() {
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(field("x", "I")), List.of()),
                cs("b/B", "a/A", List.of(field("x", "I")), List.of()));

        assertEquals("field_0", memberEntry(entries, "a/A", "x").intermediaryName());
        assertEquals("field_1", memberEntry(entries, "b/B", "x").intermediaryName());
    }

    @Test
    void libraryClassNotMappedAndSubclassDoesNotReuseName() {
        ClassStructure library = cs("lib/L", null, List.of(), List.of(method("doIt", "()V")));
        ClassStructure input = cs("x/X", "lib/L", List.of(), List.of(method("doIt", "()V")));

        ClassHierarchyGraph graph = ClassHierarchyBuilder.build(new ClassSet(single(input), single(library)));
        List<MappingEntry> entries = MappingGenerator.generate(graph, new ObfuscationHeuristics(), null);

        // 库类零条目；输入类方法独立编号（method_0），不从库类复用。
        assertEquals(2, entries.size());
        assertEquals("method_0", memberEntry(entries, "x/X", "doIt").intermediaryName());
    }

    @Test
    void libraryAsInheritanceBridgeEnablesCrossLibraryReuse() {
        // 输入类 a/A 声明 foo；库类 lib/L 继承 a/A；输入类 x/X 继承 lib/L 并覆盖 foo。
        // 带库 jar 时祖先链 x/X → lib/L → a/A 连通，x/X.foo 复用 a/A 的中间名；
        // 无库 jar 时 lib/L 桩化（父=Object），链断开，x/X.foo 独立编号。
        ClassStructure a = cs("a/A", null, List.of(), List.of(method("foo", "()V")));
        ClassStructure library = cs("lib/L", "a/A", List.of(), List.of());
        ClassStructure x = cs("x/X", "lib/L", List.of(), List.of(method("foo", "()V")));

        // 带库：桥连通 → 复用。
        SortedMap<String, ClassStructure> inputs = new TreeMap<>();
        inputs.putAll(single(a));
        inputs.putAll(single(x));
        ClassHierarchyGraph withLib = ClassHierarchyBuilder.build(new ClassSet(inputs, single(library)));
        List<MappingEntry> withLibEntries = MappingGenerator.generate(withLib, new ObfuscationHeuristics(), null);
        assertEquals("method_0", memberEntry(withLibEntries, "x/X", "foo").intermediaryName(),
                "带库时 x/X.foo 应复用 a/A 的 method_0");

        // 无库：lib/L 桩化断链 → x/X.foo 独立编号 method_1。
        ClassHierarchyGraph withoutLib = ClassHierarchyBuilder.build(
                new ClassSet(new TreeMap<>(inputs), new TreeMap<>()));
        List<MappingEntry> withoutLibEntries = MappingGenerator.generate(withoutLib, new ObfuscationHeuristics(), null);
        assertEquals("method_1", memberEntry(withoutLibEntries, "x/X", "foo").intermediaryName(),
                "无库时继承桥缺失，x/X.foo 独立编号");
    }

    @Test
    void phantomStubProducesNoMappings() {
        List<MappingEntry> entries = generate(prefix(null), cs("x/X", "missing/Missing"));

        assertTrue(entries.stream().noneMatch(e -> "missing/Missing".equals(e.obfuscatedName())));
        assertEquals("class_0", classEntry(entries, "x/X").intermediaryName());
    }

    @Test
    void sameDescriptorMultipleMethodsInOwnerStayIndependent() {
        // UITable 回归微缩：父类 1 个 ()Z；子类 8 个不同名的独立 ()Z。
        // 类内 ()Z 不唯一 → desc 归并门控阻断 → 8 条 entry 中间名互异且均不等于祖先 method_0。
        ClassStructure.Member z1 = method("m1", "()Z");
        ClassStructure.Member z2 = method("m2", "()Z");
        ClassStructure.Member z3 = method("m3", "()Z");
        ClassStructure.Member z4 = method("m4", "()Z");
        ClassStructure.Member z5 = method("m5", "()Z");
        ClassStructure.Member z6 = method("m6", "()Z");
        ClassStructure.Member z7 = method("m7", "()Z");
        ClassStructure.Member z8 = method("m8", "()Z");
        List<MappingEntry> entries = generate(prefix(null),
                cs("p/P", null, List.of(), List.of(method("z", "()Z"))),
                cs("c/C", "p/P", List.of(),
                        List.of(z1, z2, z3, z4, z5, z6, z7, z8)));

        assertEquals("method_0", memberEntry(entries, "p/P", "z").intermediaryName());
        List<String> childNames = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String name = memberEntry(entries, "c/C", "m" + i).intermediaryName();
            assertFalse("method_0".equals(name), "c/C.m" + i + " 不得复用祖先 method_0");
            childNames.add(name);
        }
        assertEquals(8, childNames.stream().distinct().count(), "类内 ()Z 各方法中间名必须互异");
    }

    @Test
    void renamedVirtualMethodStaysIndependentAcrossGenerations() {
        // processInput 场景保真：a/A.foo()V 覆写为 b/B.foo()V（同 name:desc，收一族）；
        // 跨代再改名 c/C.m()V——完全对齐 Fabric 删 desc 归并后，method_0/method_1 独立。
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(), List.of(method("foo", "()V"))),
                cs("b/B", "a/A", List.of(), List.of(method("foo", "()V"))),
                cs("c/C", "b/B", List.of(), List.of(method("m", "()V"))));

        assertEquals("method_0", memberEntry(entries, "a/A", "foo").intermediaryName());
        assertEquals("method_0", memberEntry(entries, "b/B", "foo").intermediaryName());
        assertEquals("method_1", memberEntry(entries, "c/C", "m").intermediaryName());
    }

    @Test
    void descendantUniqueDescriptorStaysIndependent() {
        // 完全对齐 Fabric：删 desc 归并后，子类独立签名 r()V 不再吸附祖先 p/q 的号。
        List<MappingEntry> entries = generate(prefix(null),
                cs("p/P", null, List.of(), List.of(method("p", "()V"), method("q", "()V"))),
                cs("c/C", "p/P", List.of(), List.of(method("r", "()V"))));

        assertEquals("method_0", memberEntry(entries, "p/P", "p").intermediaryName());
        assertEquals("method_1", memberEntry(entries, "p/P", "q").intermediaryName());
        assertEquals("method_2", memberEntry(entries, "c/C", "r").intermediaryName());
    }

    @Test
    void bridgeMethodSharesFamilyWithSameNamedSibling() {
        // 泛型桥接：c/C 声明 foo(Ljava/lang/Object;)V（ACC_BRIDGE）与
        // foo(Ljava/lang/String;)V（实际特化），同 owner 同名 → 绑定同族，共享 method_N。
        List<MappingEntry> entries = generate(prefix(null),
                cs("a/A", null, List.of(), List.of(method("foo", "(Ljava/lang/Object;)V"))),
                cs("c/C", "a/A", List.of(),
                        List.of(method("foo", "(Ljava/lang/String;)V"),
                                new ClassStructure.Member("foo", "(Ljava/lang/Object;)V",
                                        Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC))));

        // a/A.foo(Object) 独立族 → method_0；c/C 的 String 特化与 Object 桥接
        // 经 familyKey 绑定同名非桥接方法，共享同一 method_N。
        assertEquals("method_0", memberEntry(entries, "a/A", "foo").intermediaryName());
        assertEquals("method_1", memberEntry(entries, "c/C", "foo").intermediaryName());
        List<String> cFooNames = entries.stream()
                .filter(e -> e.isMethod() && "c/C".equals(e.ownerObfuscatedName()) && "foo".equals(e.obfuscatedName()))
                .map(io.github.nanoforged.sourcesector.mapping.MappingEntry::intermediaryName)
                .toList();
        assertEquals(List.of("method_1", "method_1"), cFooNames,
                "c/C 的特化与桥接方法必须共享同一族名（fabric bridge/specialized 收敛）");
    }

    // ---- 辅助 ----

    private static List<MappingEntry> generate(String prefix, ClassStructure... classes) {
        SortedMap<String, ClassStructure> inputs = new TreeMap<>();
        for (ClassStructure structure : classes) {
            inputs.put(structure.name(), structure);
        }
        ClassHierarchyGraph graph = ClassHierarchyBuilder.build(new ClassSet(inputs, new TreeMap<>()));
        return MappingGenerator.generate(graph, new ObfuscationHeuristics(), prefix);
    }

    private static String prefix(String prefix) {
        return prefix;
    }

    private static MappingEntry classEntry(List<MappingEntry> entries, String name) {
        return entries.stream()
                .filter(e -> e.isClass() && name.equals(e.obfuscatedName()))
                .findFirst()
                .orElseThrow();
    }

    private static MappingEntry memberEntry(List<MappingEntry> entries, String owner, String name) {
        Optional<MappingEntry> found = entries.stream()
                .filter(e -> !e.isClass() && owner.equals(e.ownerObfuscatedName()) && name.equals(e.obfuscatedName()))
                .findFirst();
        assertTrue(found.isPresent(), "缺少成员: " + owner + "#" + name);
        return found.get();
    }

    private static ClassStructure cs(String name, String superName) {
        return new ClassStructure(name, superName, List.of(), List.of(), List.of());
    }

    private static ClassStructure cs(String name, String superName,
                                     List<ClassStructure.Member> fields, List<ClassStructure.Member> methods) {
        return new ClassStructure(name, superName, List.of(), fields, methods);
    }

    private static ClassStructure csWithIf(String name, String superName, List<String> interfaces,
                                           List<ClassStructure.Member> methods) {
        return new ClassStructure(name, superName, interfaces, List.of(), methods);
    }

    private static ClassStructure.Member method(String name, String desc) {
        return new ClassStructure.Member(name, desc, Opcodes.ACC_PUBLIC);
    }

    private static ClassStructure.Member field(String name, String desc) {
        return new ClassStructure.Member(name, desc, Opcodes.ACC_PUBLIC);
    }

    private static SortedMap<String, ClassStructure> single(ClassStructure structure) {
        SortedMap<String, ClassStructure> map = new TreeMap<>();
        map.put(structure.name(), structure);
        return map;
    }
}
