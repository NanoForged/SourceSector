package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.MappingUtil;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;
import net.fabricmc.mappingio.tree.MappingTree.FieldMapping;
import net.fabricmc.mappingio.tree.MappingTree.MethodMapping;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两段映射配对验证器：验证中间名表（obf → intermediary）与命名表
 * （intermediary → named）可直接组合消费（Fabric 模式：TinyRemapper 等
 * 按 obf→class_N→named 链式解析），无悬挂引用与重复目标。
 * <p>
 * 基于 mapping-io 树（{@link MemoryMappingTree}）遍历；描述符类名换算复用
 * {@link MappingUtil#mapDesc}（与 {@link MappingTreeUtil#writeProjection}
 * 的 {@code MappingSourceNsSwitch} 输出侧换算一致）。
 * <p>
 * 检查项：
 * <ol>
 *   <li>段 1 类目标唯一（class_N 不重复）；</li>
 *   <li>段 2 的类 src 全部存在于段 1 类目标；</li>
 *   <li>段 2 的每个成员 (owner, 类型, src, 描述符) 在段 1 对应 owner 下可查到
 *       （obf 侧经类映射换算）；</li>
 *   <li>段 2 描述符中的中间名类引用（com/fs/class_N 形态）全部存在于段 1 类目标。</li>
 * </ol>
 * 违规列表为空即配对完整。
 */
public final class MappingPairingValidator {

    /** 中间名类引用形态（com/fs/class_N 或 class_N）。 */
    private static final Pattern INTERMEDIARY_CLASS_REF = Pattern.compile("L((?:[a-zA-Z0-9_]+/)*class_\\d+);");

    private MappingPairingValidator() {
    }

    /**
     * 验证两段映射可配对。
     *
     * @param stage1 中间名表树（obf → intermediary）
     * @param stage2 命名表树（intermediary → named）
     * @return 违规描述列表；空列表表示配对完整
     */
    public static List<String> validate(MemoryMappingTree stage1, MemoryMappingTree stage2) {
        Objects.requireNonNull(stage1, "stage1");
        Objects.requireNonNull(stage2, "stage2");

        List<String> violations = new ArrayList<>();

        // 段 1 索引，两遍构建：先类条目（供成员 desc 换算），后成员条目。
        // 段 2 的成员 src 是中间名、desc 已换算为 intermediary 侧（writeProjection
        // 的 MappingSourceNsSwitch 类名换算），段 1 成员键须落在同一侧。
        Map<String, String> classTargets = new LinkedHashMap<>();   // class_N -> obf（唯一性）
        Map<String, String> obfToIntermediary = new LinkedHashMap<>();
        for (ClassMapping cls : stage1.getClasses()) {
            String dst = cls.getDstName(0);
            String previous = classTargets.putIfAbsent(dst, cls.getSrcName());
            if (previous != null && !previous.equals(cls.getSrcName())) {
                violations.add("段 1 类目标重复: " + dst
                        + " (" + previous + " 与 " + cls.getSrcName() + ")");
            }
            obfToIntermediary.put(cls.getSrcName(), dst);
        }
        Map<String, String> members = new LinkedHashMap<>();        // (owner, kind, inter, desc) -> obf
        for (ClassMapping cls : stage1.getClasses()) {
            String owner = cls.getSrcName();
            for (FieldMapping field : cls.getFields()) {
                members.put(memberKey(owner, "f", field.getDstName(0),
                        mapDesc(field.getSrcDesc(), obfToIntermediary)), owner);
            }
            for (MethodMapping method : cls.getMethods()) {
                members.put(memberKey(owner, "m", method.getDstName(0),
                        mapDesc(method.getSrcDesc(), obfToIntermediary)), owner);
            }
        }

        // 段 2 检查。
        for (ClassMapping cls : stage2.getClasses()) {
            String intermediaryClass = cls.getSrcName();
            if (!classTargets.containsKey(intermediaryClass)) {
                violations.add("段 2 类 src 在段 1 类目标中不存在: " + intermediaryClass);
                continue;
            }
            String ownerObf = classTargets.get(intermediaryClass);
            for (FieldMapping field : cls.getFields()) {
                if (!members.containsKey(memberKey(ownerObf, "f", field.getSrcName(), field.getSrcDesc()))) {
                    violations.add("段 2 成员在段 1 中不存在: " + intermediaryClass + "#"
                            + field.getSrcName() + field.getSrcDesc());
                }
                checkDescRefs(violations, classTargets, intermediaryClass, field.getSrcName(),
                        field.getSrcDesc());
            }
            for (MethodMapping method : cls.getMethods()) {
                if (!members.containsKey(memberKey(ownerObf, "m", method.getSrcName(), method.getSrcDesc()))) {
                    violations.add("段 2 成员在段 1 中不存在: " + intermediaryClass + "#"
                            + method.getSrcName() + method.getSrcDesc());
                }
                checkDescRefs(violations, classTargets, intermediaryClass, method.getSrcName(),
                        method.getSrcDesc());
            }
        }
        return violations;
    }

    private static void checkDescRefs(List<String> violations, Map<String, String> classTargets,
                                      String owner, String member, String descriptor) {
        if (descriptor == null) {
            return;
        }
        Matcher matcher = INTERMEDIARY_CLASS_REF.matcher(descriptor);
        while (matcher.find()) {
            if (!classTargets.containsKey(matcher.group(1))) {
                violations.add("段 2 描述符引用悬空的中间名类: " + matcher.group(1)
                        + "（成员 " + owner + "#" + member + "）");
            }
        }
    }

    private static String memberKey(String owner, String kind, String srcName, String desc) {
        return owner + '#' + kind + '#' + srcName + '#' + desc;
    }

    /** 描述符类名换算：obf 类引用 → 中间名（与 {@code MappingSourceNsSwitch} 输出侧一致）。 */
    private static String mapDesc(String descriptor, Map<String, String> obfToIntermediary) {
        if (descriptor == null || obfToIntermediary.isEmpty()) {
            return descriptor;
        }
        return MappingUtil.mapDesc(descriptor, obfToIntermediary);
    }
}
