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
 * 继承正确性：拓扑序保证父先于子，覆盖方法（同源名+描述符）从祖先映射直接复用
 * 同一中间名；同一签名在多祖先映射到不同名（菱形接口）时取字典序最小者，
 * 无需后续全局冲突解决步骤。库类与 phantom 桩不生成任何条目。
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

        for (ClassStructure.Member method : structure.methods()) {
            if (isConstructor(method)) {
                continue;
            }
            String key = memberKey(method);
            String reusedName = reusableMethodName(obfuscatedName, key);
            String methodName = reusedName != null ? reusedName : "method_" + methodCounter++;
            String readableName = heuristics.isReadableMemberName(method.name()) ? method.name() : null;
            MappingEntry entry = MappingEntry.methodEntry(
                    obfuscatedName, intermediateName, method.name(), methodName, readableName, method.desc());
            entries.add(entry);
            methodsByOwner.computeIfAbsent(obfuscatedName, ignored -> new LinkedHashMap<>()).put(key, entry);
        }
    }

    /**
     * 在祖先中查找同签名（源名+描述符）方法映射，取中间名最小者。
     * 拓扑序保证祖先已处理完毕；菱形接口多候选时字典序最小即确定性裁决。
     *
     * @param owner 当前类内部名
     * @param key   {@code name:desc}
     * @return 复用的中间名；无匹配返回 {@code null}
     */
    private String reusableMethodName(String owner, String key) {
        String best = null;
        for (String ancestor : graph.ancestorsOf(owner)) {
            Map<String, MappingEntry> ancestorMethods = methodsByOwner.get(ancestor);
            if (ancestorMethods == null) {
                continue;
            }
            MappingEntry candidate = ancestorMethods.get(key);
            if (candidate != null
                    && (best == null || candidate.intermediaryName().compareTo(best) < 0)) {
                best = candidate.intermediaryName();
            }
        }
        return best;
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
