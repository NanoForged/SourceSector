package io.github.nanoforged.sourcesector.mapping.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 类层次 DAG 构建器：从 {@link ClassSet} 生成完整的继承图。
 * <p>
 * 图必须完整才能做拓扑排序与祖先查找，因此对输入/库中引用的缺失父类/接口生成
 * phantom 桩节点（结构为 {@code null}，父为 {@code java/lang/Object}）——这是
 * "JPhantom 简易替代"：只补父链闭包，不推断成员，桩节点永不映射、永不导出。
 * <p>
 * 优先级：输入 > 库 > 桩（同名节点只建一次，输入侧结构优先）。
 * {@code java/lang/Object} 由合成根节点兜底（类文件可省略 superName 依赖它）。
 */
public final class ClassHierarchyBuilder {

    private static final String OBJECT = "java/lang/Object";

    private ClassHierarchyBuilder() {
    }

    /**
     * 构建类层次图。
     *
     * @param classes 类集合
     * @return 完整类层次图
     */
    public static ClassHierarchyGraph build(ClassSet classes) {
        Objects.requireNonNull(classes, "classes");

        Map<String, ClassStructure> inputs = classes.inputs();
        Map<String, ClassStructure> libraries = classes.libraries();

        TreeMap<String, ClassHierarchyGraph.Node> nodes = new TreeMap<>();
        for (Map.Entry<String, ClassStructure> entry : inputs.entrySet()) {
            nodes.put(entry.getKey(), new ClassHierarchyGraph.Node(entry.getValue(), true));
        }
        for (Map.Entry<String, ClassStructure> entry : libraries.entrySet()) {
            nodes.putIfAbsent(entry.getKey(), new ClassHierarchyGraph.Node(entry.getValue(), false));
        }
        nodes.putIfAbsent(OBJECT, new ClassHierarchyGraph.Node(null, false));

        // 补桩：输入/库引用的缺失父类/接口 → 空结构节点（父为 Object，无需递归补桩）。
        for (ClassHierarchyGraph.Node node : List.copyOf(nodes.values())) {
            if (node.structure() == null) {
                continue;
            }
            for (String parent : parentsOf(node.structure())) {
                nodes.putIfAbsent(parent, new ClassHierarchyGraph.Node(null, false));
            }
        }

        // 父邻接（桩节点父为 Object；Object 本身无父）。
        Map<String, List<String>> parents = new LinkedHashMap<>();
        for (Map.Entry<String, ClassHierarchyGraph.Node> entry : nodes.entrySet()) {
            String name = entry.getKey();
            ClassHierarchyGraph.Node node = entry.getValue();
            parents.put(name, OBJECT.equals(name)
                    ? List.of()
                    : node.structure() != null ? parentsOf(node.structure()) : List.of(OBJECT));
        }

        // 子邻接由父邻接反推（桩节点无法自报孩子）。
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (String name : nodes.keySet()) {
            children.put(name, new ArrayList<>());
        }
        for (Map.Entry<String, List<String>> entry : parents.entrySet()) {
            for (String parent : entry.getValue()) {
                children.get(parent).add(entry.getKey());
            }
        }

        return new ClassHierarchyGraph(nodes, parents, children);
    }

    private static List<String> parentsOf(ClassStructure structure) {
        List<String> parents = new ArrayList<>();
        if (structure.superName() != null) {
            parents.add(structure.superName());
        }
        parents.addAll(structure.interfaces());
        return parents;
    }
}
