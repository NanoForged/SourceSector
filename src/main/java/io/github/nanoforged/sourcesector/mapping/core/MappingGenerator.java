package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.mapping.core.ClassStructure;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 中间名映射生成器：按拓扑序为全部输入类分配统一中间名。
 * <p>
 * 命名规则：类 {@code class_N}、字段 {@code field_N}、方法 {@code method_N}，
 * 每类各用独立全局计数器（Fabric Intermediary 惯例，名称全局唯一）；
 * 类名可选置于 {@code prefix} 包路径下（如 {@code com/example/out/class_0}）。
 * <p>
 * 方法族收敛对齐 Fabric/Stitch 语义：以 {@code name:desc} 为键，声明者集合在
 * 类继承无向图的连通分量（superclass 链 ∪ 接口闭包 ∪ 后代闭包互达）内归并为
 * 同一个方法族，族内共享同一个 {@code method_N}。这取代旧版「精确键 + 描述符
 * 签名族归并」的三级裁决——Fabric 从不按描述符合并，只按精确 {@code name:desc}
 * 沿继承树连接。私有/静态方法不参与族归并，独立发号。
* 泛型桥接方法（{@code ACC_BRIDGE}）绑定到同 owner 内同名非桥接方法，与其共享
     * 族（对齐 Fabric 的 bridge/specialized 收敛）；同时桥接方法自身的
     * {@code name:bridgeDesc} 键与兄弟特化键在键级等价（跨类族合并：接口泛型族
     * 与各实现类的特化族收敛为同一 method_N）。库类（mapped=false 但有结构）参与
     * 族归并充当继承/接口伞点，但不生成条目；phantom 桩（无结构）不参与。
 * 构造方法与静态初始化块（{@code <init>}/{@code <clinit>}）不映射。
 * <p>
 * 可读名回写：原始名通过 {@link ObfuscationHeuristics} 判定为未混淆（可读）时，
 * 条目携带 named 列（可读名 = 原始名），供反向映射文件使用。
 * <p>
 * 确定性：族归并前按拓扑序 × 字典序排序，命名发放顺序 = 拓扑序 × 类文件声明
 * 顺序，同输入必然产出相同编号。
 */
public final class MappingGenerator {

    private final ClassHierarchyGraph graph;
    private final ObfuscationHeuristics heuristics;
    private final String prefix;

    private int classCounter;
    private int fieldCounter;
    private int methodCounter;
    private final List<MappingEntry> entries = new ArrayList<>();
    /** 方法族：owner 内部名 → (name:desc → 族 id)。 */
    private final Map<String, Map<String, Integer>> familyIdByOwner = new LinkedHashMap<>();
    /** 方法族 id → 已分配的中间名；族内首个成员分配后其余复用。 */
    private final List<String> familyNames = new ArrayList<>();
    /** owner 的继承可达集合（祖先 ∪ 后代）缓存，供族归并判定。 */
    private final Map<String, Set<String>> reachableCache = new LinkedHashMap<>();
    /** 键级并查集：{@code name:bridgeDesc}（接口族）与同 owner 兄弟特化键等价，父键字典序小者为根。 */
    private final Map<String, String> familyKeyParent = new LinkedHashMap<>();
    /** 拓扑序（含全部节点），供确定性归并。 */
    private List<String> topologicalOrder;

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
        topologicalOrder = graph.topologicalOrder();
        buildMethodFamilies();
        for (String name : topologicalOrder) {
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

    /**
     * 预构建方法族：以 {@code name:desc} 为键，把继承可达的声明者归并为同一族。
     * <p>
     * 对齐 Stitch Stage 3：对每个键收集全部非私有/非静态/非构造的声明者，两个
     * 声明者若一方在另一方的祖先（superclass ∪ 接口）或后代（subclass ∪ 实现者）
     * 闭合内则属同一族。族内中间类不声明该键也允许穿越；私有/静态方法独立发号。
     */
    private void buildMethodFamilies() {
        Map<String, List<String>> declarersByKey = new LinkedHashMap<>();
        // 收集方向二：库类（mapped=false 但有结构）也参与声明者集合，充当跨输入
        // 树的继承/接口伞点；phantom 桩（structure==null）无成员，跳过。
        for (String name : topologicalOrder) {
            ClassStructure structure = graph.structureOf(name);
            if (structure == null) {
                continue;
            }
            for (ClassStructure.Member method : structure.methods()) {
                if (isConstructor(method) || isPrivate(method) || isStatic(method)) {
                    continue;
                }
                String key = familyKey(structure, method);
                declarersByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(name);
                // 收集方向一：桥接方法将其 {@code name:bridgeDesc} 键（接口族）与
                // 同 owner 兄弟特化键等价——键级 union，供后续跨键族合并。
                if (isBridge(method) && !key.equals(memberKey(method))) {
                    unionFamilyKey(memberKey(method), key);
                }
            }
        }

        // 键等价归并：把桥接等价键合并成同一组，声明者集合取并集后统一聚类。
        Map<String, List<String>> mergedDeclarers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : declarersByKey.entrySet()) {
            String root = familyKeyRoot(entry.getKey());
            mergedDeclarers.computeIfAbsent(root, ignored -> new ArrayList<>()).addAll(entry.getValue());
        }

        // 每（合并后）键的声明者按连通分量归并：两个声明者互达（一方在另一方
        // 祖先/后代闭包内）则同族，并查集聚类；分量内以拓扑序最前者为代表。
        for (Map.Entry<String, List<String>> entry : mergedDeclarers.entrySet()) {
            String key = entry.getKey();
            List<String> declarers = entry.getValue();
            int size = declarers.size();
            if (size == 1) {
                registerFamily(key, declarers.getFirst(), declarers.getFirst());
                continue;
            }
            int[] parent = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
            for (int i = 0; i < size; i++) {
                Set<String> reachable = reachableSet(declarers.get(i));
                for (int j = i + 1; j < size; j++) {
                    if (reachable.contains(declarers.get(j))) {
                        unionInto(parent, i, j);
                    }
                }
            }
            Set<Integer> componentRepresentative = new HashSet<>();
            Map<Integer, String> componentRepresentativeMap = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                int root = findRoot(parent, i);
                if (componentRepresentative.add(root)) {
                    componentRepresentativeMap.put(root, declarers.get(i));
                }
            }
            for (int i = 0; i < size; i++) {
                int root = findRoot(parent, i);
                registerFamily(key, componentRepresentativeMap.get(root), declarers.get(i));
            }
        }
    }

    /** 桥接等价键并查集：{@code bridgeKey}（接口族键）与 {@code siblingKey}（特化键）归一族，父键取字典序小者。 */
    private void unionFamilyKey(String bridgeKey, String siblingKey) {
        String rootA = familyKeyRoot(bridgeKey);
        String rootB = familyKeyRoot(siblingKey);
        if (rootA.equals(rootB)) {
            return;
        }
        if (rootB.compareTo(rootA) < 0) {
            familyKeyParent.put(rootA, rootB);
        } else {
            familyKeyParent.put(rootB, rootA);
        }
    }

    /** 键级并查集根查找（带路径压缩）。 */
    private String familyKeyRoot(String key) {
        List<String> chain = new java.util.ArrayList<>();
        String root = key;
        while (true) {
            String parent = familyKeyParent.get(root);
            if (parent == null || parent.equals(root)) {
                for (String node : chain) {
                    familyKeyParent.put(node, root);
                }
                return root;
            }
            chain.add(root);
            root = parent;
        }
    }

    /** 把声明者归入代表类的方法族：代表族号缺失则新建，其余成员复用代表族号。 */
    private void registerFamily(String key, String representative, String declarer) {
        Map<String, Integer> repFamilies = familyIdByOwner.computeIfAbsent(representative,
                ignored -> new LinkedHashMap<>());
        Integer repFamily = repFamilies.get(key);
        if (repFamily == null) {
            repFamily = familyNames.size();
            repFamilies.put(key, repFamily);
            familyNames.add(null);
        }
        if (!representative.equals(declarer)) {
            familyIdByOwner.computeIfAbsent(declarer, ignored -> new LinkedHashMap<>()).put(key, repFamily);
        }
    }

    /** owner 的继承可达集合（祖先 ∪ 后代，含接口/实现者），带缓存。 */
    private Set<String> reachableSet(String owner) {
        Set<String> reachable = reachableCache.get(owner);
        if (reachable == null) {
            reachable = new HashSet<>(graph.ancestorsOf(owner));
            reachable.addAll(graph.descendantsOf(owner));
            reachableCache.put(owner, reachable);
        }
        return reachable;
    }

    private static int findRoot(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void unionInto(int[] parent, int a, int b) {
        int rootA = findRoot(parent, a);
        int rootB = findRoot(parent, b);
        if (rootA != rootB) {
            parent[rootA] = rootB;
        }
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

        Map<String, Integer> ownerFamilies = familyIdByOwner.get(obfuscatedName);
        for (ClassStructure.Member method : structure.methods()) {
            if (isConstructor(method)) {
                continue;
            }
            String key = familyKeyRoot(familyKey(structure, method));
            String methodName;
            Integer familyId = ownerFamilies == null ? null : ownerFamilies.get(key);
            if (familyId == null) {
                methodName = "method_" + methodCounter++; // 私有/静态/无族方法独立发号
            } else if (familyNames.get(familyId) == null) {
                methodName = "method_" + methodCounter++;
                familyNames.set(familyId, methodName);
            } else {
                methodName = familyNames.get(familyId);
            }
            String readableName = heuristics.isReadableMemberName(method.name()) ? method.name() : null;
            MappingEntry entry = MappingEntry.methodEntry(
                    obfuscatedName, intermediateName, method.name(), methodName, readableName, method.desc());
            entries.add(entry);
        }
    }

    private static boolean isPrivate(ClassStructure.Member method) {
        return (method.access() & Opcodes.ACC_PRIVATE) != 0;
    }

    private static boolean isStatic(ClassStructure.Member method) {
        return (method.access() & Opcodes.ACC_STATIC) != 0;
    }

    /**
     * 方法族键：默认 {@code name:desc}；{@code ACC_BRIDGE} 泛型桥接方法绑定到
     * 同 owner 内同名非桥接方法（对齐 Fabric 的 bridge/specialized 同族收敛）。
     *
     * @param structure 所属类结构
     * @param method    方法
     * @return 族键
     */
    private static String familyKey(ClassStructure structure, ClassStructure.Member method) {
        if (isBridge(method)) {
            for (ClassStructure.Member candidate : structure.methods()) {
                if (candidate != method
                        && candidate.name().equals(method.name())
                        && !isBridge(candidate)
                        && !isConstructor(candidate)) {
                    return memberKey(candidate);
                }
            }
        }
        return memberKey(method);
    }

    private static boolean isBridge(ClassStructure.Member method) {
        return (method.access() & Opcodes.ACC_BRIDGE) != 0;
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
