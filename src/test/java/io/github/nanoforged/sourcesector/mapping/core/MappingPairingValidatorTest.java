package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MappingPairingValidator} 测试：两段映射组合消费的完整性验证——
 * 完美配对零违规，各类损坏（类悬挂、成员缺失、desc 引用悬空、目标重复）逐一检出。
 */
class MappingPairingValidatorTest {

    /** 段 1 树：obf → intermediary。 */
    private static MemoryMappingTree stage1(MappingEntry... entries) {
        return MappingTreeUtil.fromEntries(List.of(entries), "obf", List.of("intermediary"));
    }

    /** 段 2 树：intermediary → named。 */
    private static MemoryMappingTree stage2(MappingEntry... entries) {
        return MappingTreeUtil.fromEntries(List.of(entries), "intermediary", List.of("named"));
    }

    @Test
    void perfectPairingZeroViolations() {
        MemoryMappingTree s1 = stage1(
                MappingEntry.classEntry("a/A", "com/fs/class_0", null),
                MappingEntry.methodEntry("a/A", "com/fs/class_0", "m1", "method_0", null, "(La/A;)V"),
                MappingEntry.classEntry("b/B", "com/fs/class_1", null),
                MappingEntry.fieldEntry("b/B", "com/fs/class_1", "f1", "field_0", null, "Lcom/fs/class_0;"));
        // 段 2 的 desc 已换算为 intermediary 侧（writeProjection 类名换算）。
        MemoryMappingTree s2 = stage2(
                MappingEntry.classEntry("com/fs/class_0", null, "NamedA"),
                MappingEntry.methodEntry("com/fs/class_0", "NamedA", "method_0", null, "render",
                        "(Lcom/fs/class_0;)V"),
                MappingEntry.classEntry("com/fs/class_1", null, "NamedB"),
                MappingEntry.fieldEntry("com/fs/class_1", "NamedB", "field_0", null, "speed",
                        "Lcom/fs/class_0;"));

        assertEquals(List.of(), MappingPairingValidator.validate(s1, s2));
    }

    @Test
    void stage2ClassSrcDanglingDetected() {
        MemoryMappingTree s1 = stage1(MappingEntry.classEntry("a/A", "com/fs/class_0", null));
        MemoryMappingTree s2 = stage2(
                MappingEntry.classEntry("com/fs/class_0", null, "NamedA"),
                MappingEntry.classEntry("com/fs/class_99", null, "Ghost"));

        List<String> violations = MappingPairingValidator.validate(s1, s2);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("com/fs/class_99"), violations.toString());
    }

    @Test
    void stage2MemberMissingInStage1Detected() {
        MemoryMappingTree s1 = stage1(
                MappingEntry.classEntry("a/A", "com/fs/class_0", null),
                MappingEntry.methodEntry("a/A", "com/fs/class_0", "m1", "method_0", null, "()V"));
        MemoryMappingTree s2 = stage2(
                MappingEntry.classEntry("com/fs/class_0", null, "NamedA"),
                MappingEntry.methodEntry("com/fs/class_0", "NamedA", "method_0", null, "render", "()V"),
                MappingEntry.methodEntry("com/fs/class_0", "NamedA", "method_7", null, "ghost", "()V"));

        List<String> violations = MappingPairingValidator.validate(s1, s2);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("method_7"), violations.toString());
    }

    @Test
    void stage2DescriptorDanglingIntermediaryClassDetected() {
        MemoryMappingTree s1 = stage1(
                MappingEntry.classEntry("a/A", "com/fs/class_0", null),
                MappingEntry.methodEntry("a/A", "com/fs/class_0", "m1", "method_0", null, "()V"));
        MemoryMappingTree s2 = stage2(
                MappingEntry.classEntry("com/fs/class_0", null, "NamedA"),
                MappingEntry.methodEntry("com/fs/class_0", "NamedA", "method_0", null, "render",
                        "(Lcom/fs/class_42;)V"));

        List<String> violations = MappingPairingValidator.validate(s1, s2);

        // 成员 desc 与段 1（换算后 ()V）不匹配 → 成员缺失违规；class_42 不在类目标 → desc 悬空违规。
        assertEquals(2, violations.size());
        assertTrue(violations.stream().anyMatch(v -> v.contains("描述符") && v.contains("class_42")),
                violations.toString());
    }

    @Test
    void stage1DuplicateClassTargetDetected() {
        MemoryMappingTree s1 = stage1(
                MappingEntry.classEntry("a/A", "com/fs/class_0", null),
                MappingEntry.classEntry("b/B", "com/fs/class_0", null));
        MemoryMappingTree s2 = stage2();

        List<String> violations = MappingPairingValidator.validate(s1, s2);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("重复"), violations.toString());
    }

    @Test
    void libraryClassReferenceNotFalsePositive() {
        // desc 中的库类（未映射）保持 obf 形态，不属于中间名引用，不误报。
        MemoryMappingTree s1 = stage1(
                MappingEntry.classEntry("a/A", "com/fs/class_0", null),
                MappingEntry.methodEntry("a/A", "com/fs/class_0", "m1", "method_0", null,
                        "(Ljava/util/List;)V"));
        MemoryMappingTree s2 = stage2(
                MappingEntry.classEntry("com/fs/class_0", null, "NamedA"),
                MappingEntry.methodEntry("com/fs/class_0", "NamedA", "method_0", null, "render",
                        "(Ljava/util/List;)V"));

        assertEquals(List.of(), MappingPairingValidator.validate(s1, s2));
    }
}
