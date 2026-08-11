package io.github.nanoforged.sourcesector.mapping.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * 类层次 DAG：节点、父/子邻接与确定性查询。
 * <p>
 * 确定性：所有内部结构使用 {@link TreeMap}（内部名字典序）或按类文件声明顺序
 * 构建的列表；拓扑排序采用 Kahn 算法 + 按内部名字典序的优先队列——同输入多次
 * 运行产出完全相同的节点顺序，这是中间名编号确定性的第二层保证。
 */
public final class ClassHierarchyGraph {

    /**
     * 图节点。
     *
     * @param structure 类结构；{@code null} 表示 phantom 桩（缺失依赖）
     * @param mapped    是否来自输入 jar（需要映射）
     */
    public record Node(ClassStructure structure, boolean mapped) {
    }

    private final TreeMap<String, Node> nodes;
    private final Map<String, List<String>> parents;
    private final Map<String, List<String>> children;
    private final Map<String, List<String>> ancestorsCache = new LinkedHashMap<>();   // 祖先结果缓存
    private final Map<String, List<String>> descendantsCache = new LinkedHashMap<>(); // 后代结果缓存

    ClassHierarchyGraph(TreeMap<String, Node> nodes,
                        Map<String, List<String>> parents,
                        Map<String, List<String>> children) {
        this.nodes = nodes;
        this.parents = parents;
        this.children = children;
    }

    /**
     * 返回全节点拓扑序（父先于子）。
     * <p>
     * Kahn 算法：就绪队列按内部名字典序弹出（优先队列），顺序完全确定；
     * 存在环时抛出错误并列出未处理节点。
     *
     * @return 节点内部名列表
     * @throws SourceSectorException 类层次存在环
     */
    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : parents.entrySet()) {
            indegree.put(entry.getKey(), entry.getValue().size());
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String name = ready.poll();
            order.add(name);
            for (String child : children.get(name)) {
                int remaining = indegree.computeIfPresent(child, (key, value) -> value - 1);
                if (remaining == 0) {
                    ready.add(child);
                }
            }
        }

        if (order.size() != nodes.size()) {
            List<String> unresolved = new ArrayList<>();
            for (String name : nodes.keySet()) {
                if (!order.contains(name)) {
                    unresolved.add(name);
                }
            }
            throw new SourceSectorException(
                    "类层次图存在环，无法拓扑排序，未处理节点: " + String.join(", ", unresolved));
        }
        return List.copyOf(order);
    }

    /**
     * 返回节点的全部祖先（含间接父类/接口），排除自身。
     * <p>
     * 遍历顺序确定：BFS 按父邻接顺序（父类在前、接口按声明顺序），去重后缓存；
     * 顺序本身不参与命名决策（冲突时按目标名字典序取最小），集合恒定即足够。
     *
     * @param internalName 节点内部名
     * @return 祖先内部名列表
     */
    public List<String> ancestorsOf(String internalName) {
        return ancestorsCache.computeIfAbsent(internalName, this::computeAncestors);
    }

    private List<String> computeAncestors(String internalName) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(parents.getOrDefault(internalName, List.of()));
        while (!queue.isEmpty()) {
            String parent = queue.poll();
            if (seen.add(parent)) {
                queue.addAll(parents.getOrDefault(parent, List.of()));
            }
        }
        return List.copyOf(seen);
    }

    /**
     * 返回沿 {@code superName} 单值链的直系超类序列（近→远），排除自身。
     * <p>
     * 遵循 JVM 方法覆写解析顺序：实现类优先从最近超类继承方法，superclass 链的
     * 命中优先级应高于接口。遇缺失父类（phantom 桩）或 {@code java/lang/Object}
     * 即终止。仅代表类文件 {@code super_name} 的单一链，不含接口。
     *
     * @param internalName 节点内部名
     * @return 超类链内部名列表（近→远）
     */
    public List<String> superChainOf(String internalName) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(internalName);
        List<String> chain = new ArrayList<>();
        String current = internalName;
        while (true) {
            ClassStructure structure = structureOf(current);
            if (structure == null || structure.superName() == null) {
                break;
            }
            String superName = structure.superName();
            if ("java/lang/Object".equals(superName) || !visited.add(superName)) {
                break;
            }
            chain.add(superName);
            current = superName;
        }
        return List.copyOf(chain);
    }

    /**
     * 返回接口闭包：直接接口 + 递归其父接口，按声明顺序深度优先去重。
     * <p>
     * 用于接口实现一致化：多个接口声明同签名方法时，取声明序第一个命中的
     * 接口族（含其父接口）即确定性裁决，与 JVM 接口方法解析顺序一致。
     * 遇缺失接口（phantom 桩）即停止该分支的递归。
     *
     * @param internalName 节点内部名
     * @return 接口闭包内部名列表（声明序深度优先）
     */
    public List<String> interfaceClosureOf(String internalName) {
        LinkedHashSet<String> closure = new LinkedHashSet<>();
        ClassStructure structure = structureOf(internalName);
        if (structure != null) {
            collectInterfaceClosure(structure.interfaces(), closure);
        }
        return List.copyOf(closure);
    }

    private void collectInterfaceClosure(List<String> interfaces, LinkedHashSet<String> closure) {
        for (String iface : interfaces) {
            if (closure.add(iface)) {
                ClassStructure structure = structureOf(iface);
                if (structure != null) {
                    collectInterfaceClosure(structure.interfaces(), closure);
                }
            }
        }
    }

    /**
     * 返回直接子类/实现者（单层），按构建时的字典序确定顺序。
     * <p>
     * 对普通类为直接子类，对接口为直接实现类及扩展该接口的子接口；
     * 顺序源自 {@code children} 邻接（由父邻接反推时的字典序插入），完全确定。
     *
     * @param internalName 节点内部名
     * @return 直接子类/实现者内部名列表
     */
    public List<String> childrenOf(String internalName) {
        return children.getOrDefault(internalName, List.of());
    }

    /**
     * 返回全部后代闭包：直接子类/实现者 + 其递归后代，排除自身。
     * <p>
     * 遍历顺序确定：按 {@code children} 邻接顺序 BFS 去重，结果缓存。
     * 与 {@link #ancestorsOf} 互补，共同构成 Stitch 式方法族的双向可达判定。
     *
     * @param internalName 节点内部名
     * @return 后代内部名列表（含间接）
     */
    public List<String> descendantsOf(String internalName) {
        return descendantsCache.computeIfAbsent(internalName, this::computeDescendants);
    }

    private List<String> computeDescendants(String internalName) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(children.getOrDefault(internalName, List.of()));
        while (!queue.isEmpty()) {
            String child = queue.poll();
            if (seen.add(child)) {
                queue.addAll(children.getOrDefault(child, List.of()));
            }
        }
        return List.copyOf(seen);
    }

    /**
     * 判定节点是否来自输入 jar（需要生成映射）。
     *
     * @param internalName 节点内部名
     * @return true 表示该节点需要映射
     */
    public boolean isMapped(String internalName) {
        Node node = nodes.get(internalName);
        return node != null && node.mapped();
    }

    /**
     * 返回节点的类结构。
     *
     * @param internalName 节点内部名
     * @return 类结构；桩节点或未知节点返回 {@code null}
     */
    public ClassStructure structureOf(String internalName) {
        Node node = nodes.get(internalName);
        return node == null ? null : node.structure();
    }

    /**
     * 返回全部节点内部名（按字典序）。
     *
     * @return 节点内部名集合
     */
    public Set<String> nodeNames() {
        return nodes.keySet();
    }

    /**
     * 判定两个名称是否表示同一节点（存在性检查，供防御性使用）。
     *
     * @param internalName 节点内部名
     * @return true 表示节点存在
     */
    public boolean contains(String internalName) {
        return nodes.containsKey(internalName);
    }
}
