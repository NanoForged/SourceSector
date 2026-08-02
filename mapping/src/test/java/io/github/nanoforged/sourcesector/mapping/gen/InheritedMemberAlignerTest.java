package io.github.nanoforged.sourcesector.mapping.gen;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InheritedMemberAligner} 的逻辑验证：scope 片段按类独立翻译导致同一逻辑继承成员
 * 在声明类与子类侧得到不同 named 名（子类侧是垃圾名），对齐器必须把子类侧 remap 目标
 * 归一为声明侧目标，并对同 key 重复条目去重；声明侧缺失 / 链上无声明类时保留现状并告警。
 */
class InheritedMemberAlignerTest {
    private static ClassStructure.Member method(String name, String desc) {
        return new ClassStructure.Member(name, desc, 0);
    }

    @Test
    void divergedInheritedMemberIsAlignedToDeclaringSide() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/A", "java/lang/Object", List.of(),
                        List.of(), List.of(method("super", "(ZZ)V"))),
                new ClassStructure("a/B", "a/A", List.of(), List.of(), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/A", "C_aaa", "named/A"),
                MappingEntry.methodEntry("a/A", "named/A", "super", "m_aaa", "computeFleetPoints", "(ZZ)V"),
                MappingEntry.classEntry("a/B", "C_bbb", "named/B"),
                // 子类 scope 独立翻译：同混淆成员被赋予垃圾名 super（分叉）。
                MappingEntry.methodEntry("a/B", "named/B", "super", "m_bbb", "super", "(ZZ)V"));

        InheritedMemberAligner.AlignmentResult result = InheritedMemberAligner.align(merged, classes);

        assertEquals(1, result.replacements().size(), "分叉条目应记录一条替换: " + result.replacements());
        assertTrue(result.warnings().isEmpty(), "可对齐的分叉不应告警: " + result.warnings());
        MappingEntry bEntry = result.entries().stream()
                .filter(entry -> !entry.isClass() && "a/B".equals(entry.ownerObfuscatedName()))
                .findFirst().orElseThrow();
        assertEquals("computeFleetPoints", bEntry.namedName(), "子类侧 named 应归一为声明侧目标");
        assertEquals("m_aaa", bEntry.intermediaryName(), "intermediary 锚点应同步为声明侧");
    }

    @Test
    void declaringSideWithoutTableEntryKeepsStatusAndWarns() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/A", "java/lang/Object", List.of(),
                        List.of(), List.of(method("super", "(ZZ)V"))),
                new ClassStructure("a/B", "a/A", List.of(), List.of(), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/A", "C_aaa", "named/A"),
                MappingEntry.classEntry("a/B", "C_bbb", "named/B"),
                // 声明类 A 表内无该成员条目：无法对齐，保留现状并告警。
                MappingEntry.methodEntry("a/B", "named/B", "super", "m_bbb", "super", "(ZZ)V"));

        InheritedMemberAligner.AlignmentResult result = InheritedMemberAligner.align(merged, classes);

        assertEquals(1, result.warnings().size(), "声明侧缺失应告警: " + result.warnings());
        assertTrue(result.replacements().isEmpty(), "无声明条目不应产生替换");
        assertTrue(result.entries().stream().anyMatch(entry -> !entry.isClass()
                        && "a/B".equals(entry.ownerObfuscatedName()) && "super".equals(entry.namedName())),
                "无法对齐的条目应保留现状");
    }

    @Test
    void declaredMembersAreNotTouched() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/B", "java/lang/Object", List.of(),
                        List.of(), List.of(method("super", "(ZZ)V"))));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/B", "C_bbb", "named/B"),
                MappingEntry.methodEntry("a/B", "named/B", "super", "m_bbb", "ownName", "(ZZ)V"));

        InheritedMemberAligner.AlignmentResult result = InheritedMemberAligner.align(merged, classes);

        assertTrue(result.replacements().isEmpty(), "真实声明条目不应被修改: " + result.replacements());
        assertTrue(result.warnings().isEmpty(), "真实声明条目不应告警: " + result.warnings());
        assertEquals("ownName", result.entries().stream()
                .filter(entry -> !entry.isClass() && "a/B".equals(entry.ownerObfuscatedName()))
                .findFirst().orElseThrow().namedName());
    }

    @Test
    void chainWithoutDeclaringClassKeepsStatusAndWarns() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/A", "java/lang/Object", List.of(),
                        List.of(), List.of(method("super", "(ZZ)V"))),
                new ClassStructure("a/B", "a/A", List.of(), List.of(), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/A", "C_aaa", "named/A"),
                MappingEntry.classEntry("a/B", "C_bbb", "named/B"),
                // 索引内继承链上没有任何类声明 ghost：无法对齐，保留现状并告警。
                MappingEntry.methodEntry("a/B", "named/B", "ghost", "m_bbb", "garbage", "()V"));

        InheritedMemberAligner.AlignmentResult result = InheritedMemberAligner.align(merged, classes);

        assertEquals(1, result.warnings().size(), "链上无声明类应告警: " + result.warnings());
        assertTrue(result.entries().stream().anyMatch(entry -> !entry.isClass()
                        && "a/B".equals(entry.ownerObfuscatedName()) && "ghost".equals(entry.obfuscatedName())),
                "无法对齐的条目应保留现状");
    }

    @Test
    void duplicateKeysAreMergedAfterAlignment() {
        List<ClassStructure> classes = List.of(
                new ClassStructure("a/A", "java/lang/Object", List.of(),
                        List.of(), List.of(method("super", "(ZZ)V"))),
                new ClassStructure("a/B", "a/A", List.of(), List.of(), List.of()));
        List<MappingEntry> merged = List.of(
                MappingEntry.classEntry("a/A", "C_aaa", "named/A"),
                MappingEntry.methodEntry("a/A", "named/A", "super", "m_aaa", "computeFleetPoints", "(ZZ)V"),
                MappingEntry.classEntry("a/B", "C_bbb", "named/B"),
                // 同 key 两条：scope 垃圾名条目 + 继承传播别名（声明侧目标），对齐后应合并为一条。
                MappingEntry.methodEntry("a/B", "named/B", "super", "m_bbb", "super", "(ZZ)V"),
                MappingEntry.methodEntry("a/B", "named/B", "super", "m_aaa", "computeFleetPoints", "(ZZ)V"));

        InheritedMemberAligner.AlignmentResult result = InheritedMemberAligner.align(merged, classes);

        List<MappingEntry> bEntries = result.entries().stream()
                .filter(entry -> !entry.isClass() && "a/B".equals(entry.ownerObfuscatedName())
                        && "super".equals(entry.obfuscatedName()) && "(ZZ)V".equals(entry.descriptor()))
                .toList();
        assertEquals(1, bEntries.size(), "对齐后同 key 重复条目应合并为一条: " + bEntries);
        assertEquals("computeFleetPoints", bEntries.get(0).namedName());
    }
}
