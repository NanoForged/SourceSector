package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.mapping.core.ClassStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 中间名映射生成器：按拓扑序为全部输入类分配统一中间名。
 * <p>
 * 命名规则：类 {@code class_N}、字段 {@code field_N}、方法 {@code method_N}，
 * 每类各用独立全局计数器（Fabric Intermediary 惯例，名称全局唯一）；
 * 类名可选置于 {@code prefix} 包路径下（如 {@code com/example/out/class_0}）。
 * <p>
 * 继承正确性：拓扑序保证父先于子，覆盖方法从祖先映射直接复用同一中间名。
 * 复用裁决遵循 JVM 方法解析优先级：{@code superclass} 链（近→远）精确命中
 * 【源名+描述符】优先，其次按描述符在 superclass 链内归并（处理混淆器对
 * 同一虚方法改名的场景；仅当当前类内该描述符唯一时启用）；最后接口闭包按
 * 声明序取第一个精确命中；同签名在一棵继承树内收敛为同一个 {@code method_N}，
 * 无需后续全局冲突解决步骤。
 * 库类与 phantom 桩不生成任何条目。
 * 构造方法与静态初始化块（{@code <init>}/{@code <clinit>}）不映射。
 * <p>
 * 可读名回写：原始名通过 {@link ObfuscationHeuristics} 判定为未混淆（可读）时，
 * 条目携带 named 列（可读名 = 原始名），供反向映射文件使用。
 * <p>
 * 确定性：编号发放顺序 = 拓扑序 × 类文件声明顺序，同输入必然产出相同编号。
 */
public final class MappingGenerator {

    private final ClassHierarchyGraph graph;
    private final ObfuscationHeuristics heuristics;
    private final String prefix;

    private int classCounter;
    private int fieldCounter;
    private int methodCounter;
    private final List<MappingEntry> entries = new ArrayList<>();
    /** 已映射类的成员索引：obf 类名 → (name:desc → 方法条目)，供子类复用查询。 */
    private final Map<String, Map<String, MappingEntry>> methodsByOwner = new LinkedHashMap<>();

    private MappingGenerator(ClassHierarchyGraph graph, ObfuscationHeuristics heuristics, String prefix) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.heuristics = Objects.requireNonNull(heuristics, "heuristics");
        this.prefix = normalizePrefix(prefix);
    }

    /**
     * 生成中间名映射条目。
     *
     * @param graph     完整类层次图
     * @param heuristics 混淆名启发式
     * @param prefix    中间名包路径（内部名形式 {@code com/example/out}，可空点分形式）；
     *                  {@code null} 表示默认包
     * @return 映射条目：类按拓扑序、成员按声明顺序跟随所属类
     */
    public static List<MappingEntry> generate(ClassHierarchyGraph graph,
                                              ObfuscationHeuristics heuristics,
                                              String prefix) {
        return new MappingGenerator(graph, heuristics, prefix).run();
    }

    private List<MappingEntry> run() {
        for (String name : graph.topologicalOrder()) {
            if (!graph.isMapped(name)) {
                continue;
            }
            ClassStructure structure = graph.structureOf(name);
            if (structure == null) {
                continue;
            }
            mapClass(structure);
        }
        return entries;
    }

    private void mapClass(ClassStructure structure) {
        String obfuscatedName = structure.name();
        String intermediateName = applyPrefix("class_" + classCounter++);
        String readableClassName = heuristics.isReadableClassName(obfuscatedName) ? obfuscatedName : null;
        entries.add(MappingEntry.classEntry(obfuscatedName, intermediateName, readableClassName));

        for (ClassStructure.Member field : structure.fields()) {
            String fieldName = "field_" + fieldCounter++;
            String readableName = heuristics.isReadableMemberName(field.name()) ? field.name() : null;
            entries.add(MappingEntry.fieldEntry(
                    obfuscatedName, intermediateName, field.name(), fieldName, readableName, field.desc()));
        }

        Map<String, Integer> descriptorCounts = countDescriptors(structure);
        for (ClassStructure.Member method : structure.methods()) {
            if (isConstructor(method)) {
                continue;
            }
            String key = memberKey(method);
            boolean descriptorMergeEligible = descriptorCounts.getOrDefault(method.desc(), 0) == 1;
            String reusedName = reusableMethodName(obfuscatedName, key, method.desc(), descriptorMergeEligible);
            String methodName = reusedName != null ? reusedName : "method_" + methodCounter++;
            String readableName = heuristics.isReadableMemberName(method.name()) ? method.name() : null;
            MappingEntry entry = MappingEntry.methodEntry(
                    obfuscatedName, intermediateName, method.name(), methodName, readableName, method.desc());
            entries.add(entry);
            methodsByOwner.computeIfAbsent(obfuscatedName, ignored -> new LinkedHashMap<>()).put(key, entry);
        }
    }

    /**
     * 在祖先中寻找可复用的中间名，遵循 JVM 方法解析优先级。
     * <p>
     * 优先级：1) {@code superclass} 链按精确 {@code name:desc}（近→远）；
     * 2) {@code superclass} 链按描述符匹配（签名族归并，处理同一虚方法在
     * 不同类混淆名不一致的情况，如 {@code processInput} vs {@code super}；仅当
     * 当前类内该描述符唯一时启用，避免把类内多个独立同签名方法吸附到同一中间名）；
     * 3) 接口闭包按精确 {@code name:desc}，取声明序第一个命中。
     * <p>
     * 拓扑序保证祖先已处理完毕。命中即返回最近祖先的第一个匹配，
     * 不再采用字典序最小裁决（避免跨继承树误选）。
     *
     * @param owner 当前类内部名
     * @param key   {@code name:desc}
     * @param desc  方法描述符
     * @param descriptorMergeEligible 当前类内该描述符是否唯一（唯一才允许按描述符归并）
     * @return 复用的中间名；无匹配返回 {@code null}
     */
    private String reusableMethodName(String owner, String key, String desc, boolean descriptorMergeEligible) {
        for (String ancestor : graph.superChainOf(owner)) {
            String match = methodNameByKey(ancestor, key);
            if (match != null) {
                return match;
            }
        }
        if (descriptorMergeEligible) {
            for (String ancestor : graph.superChainOf(owner)) {
                String match = methodNameByDescriptor(ancestor, desc);
                if (match != null) {
                    return match;
                }
            }
        }
        for (String iface : graph.interfaceClosureOf(owner)) {
            String match = methodNameByKey(iface, key);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    /** 在指定已映射类的方法索引中按精确 {@code name:desc} 查找。 */
    private String methodNameByKey(String owner, String key) {
        Map<String, MappingEntry> ownerMethods = methodsByOwner.get(owner);
        if (ownerMethods == null) {
            return null;
        }
        MappingEntry candidate = ownerMethods.get(key);
        return candidate == null ? null : candidate.intermediaryName();
    }

    /** 在指定已映射类的方法索引中按描述符查找（签名族归并）。 */
    private String methodNameByDescriptor(String owner, String desc) {
        Map<String, MappingEntry> ownerMethods = methodsByOwner.get(owner);
        if (ownerMethods == null) {
            return null;
        }
        for (MappingEntry entry : ownerMethods.values()) {
            if (desc.equals(entry.descriptor())) {
                return entry.intermediaryName();
            }
        }
        return null;
    }

    /** 统计当前类内各方法描述符出现次数（跳过构造方法），供 desc 归并门控使用。 */
    private static Map<String, Integer> countDescriptors(ClassStructure structure) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ClassStructure.Member method : structure.methods()) {
            if (isConstructor(method)) {
                continue;
            }
            counts.merge(method.desc(), 1, Integer::sum);
        }
        return counts;
    }

    private static boolean isConstructor(ClassStructure.Member method) {
        return "<init>".equals(method.name()) || "<clinit>".equals(method.name());
    }

    private static String memberKey(ClassStructure.Member member) {
        return member.name() + ':' + member.desc();
    }

    private String applyPrefix(String name) {
        return prefix == null ? name : prefix + '/' + name;
    }

    /** 前缀归一化：点分转内部名形式、去首尾斜杠；空白视为未指定。 */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        String normalized = prefix.replace('.', '/');
        int start = 0;
        int end = normalized.length();
        while (start < end && normalized.charAt(start) == '/') {
            start++;
        }
        while (end > start && normalized.charAt(end - 1) == '/') {
            end--;
        }
        return start >= end ? null : normalized.substring(start, end);
    }
}
