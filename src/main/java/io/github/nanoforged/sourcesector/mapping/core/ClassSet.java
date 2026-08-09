package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.core.ClassStructure;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 类集合：输入（待映射）与库（仅参与继承分析，不映射）分开收集。
 * <p>
 * 两个映射均为按内部名排序的不可变视图——编号与 jar 传入顺序、zip 条目顺序无关，
 * 是确定性输出的第一层保证。
 *
 * @param inputs    输入 jar 中的类，内部名 → 结构
 * @param libraries 库 jar 中的类，内部名 → 结构
 */
public record ClassSet(SortedMap<String, ClassStructure> inputs,
                       SortedMap<String, ClassStructure> libraries) {

    /**
     * 构造类集合（内部按内部名排序并冻结）。
     *
     * @param inputs    输入类映射
     * @param libraries 库类映射
     */
    public ClassSet {
        inputs = Collections.unmodifiableSortedMap(new TreeMap<>(inputs));
        libraries = Collections.unmodifiableSortedMap(new TreeMap<>(libraries));
    }
}
