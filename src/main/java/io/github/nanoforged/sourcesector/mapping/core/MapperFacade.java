package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 映射管线门面：串起 ClassProvider → ClassHierarchyBuilder → MappingGenerator。
 * <p>
 * 单一职责：编排与统计；各阶段可独立替换（注入不同启发式等），组件间无隐式耦合。
 */
public final class MapperFacade {

    private final ObfuscationHeuristics heuristics;

    /**
     * 创建门面（默认启发式）。
     */
    public MapperFacade() {
        this(new ObfuscationHeuristics());
    }

    /**
     * 创建门面。
     *
     * @param heuristics 混淆名启发式
     */
    public MapperFacade(ObfuscationHeuristics heuristics) {
        this.heuristics = Objects.requireNonNull(heuristics, "heuristics");
    }

    /**
     * 从 jar 路径生成映射条目。
     *
     * @param inputJars   输入混淆 jar
     * @param libraryJars 库 jar
     * @param prefix      中间名包路径（可空）
     * @return 映射结果
     * @throws IOException 读取 jar 失败
     */
    public MapperResult generateMappings(List<Path> inputJars, List<Path> libraryJars, String prefix)
            throws IOException {
        ClassSet classes = ClassProvider.load(inputJars, libraryJars);
        return generateMappings(classes, prefix);
    }

    /**
     * 从类集合生成映射条目。
     *
     * @param classes 类集合
     * @param prefix  中间名包路径（可空）
     * @return 映射结果
     */
    public MapperResult generateMappings(ClassSet classes, String prefix) {
        ClassHierarchyGraph graph = ClassHierarchyBuilder.build(classes);
        List<MappingEntry> entries = MappingGenerator.generate(graph, heuristics, prefix);
        int mappedClasses = 0;
        int mappedMethods = 0;
        int mappedFields = 0;
        int readable = 0;
        for (MappingEntry entry : entries) {
            if (entry.isClass()) {
                mappedClasses++;
            } else if (entry.isMethod()) {
                mappedMethods++;
            } else {
                mappedFields++;
            }
            if (entry.namedName() != null) {
                readable++;
            }
        }
        return new MapperResult(List.copyOf(entries), mappedClasses, mappedMethods, mappedFields, readable);
    }

    /**
     * 映射结果。
     *
     * @param entries       映射条目（拓扑序）
     * @param mappedClasses 映射类数
     * @param mappedMethods 映射方法数
     * @param mappedFields  映射字段数
     * @param readableCount 携带可读名回写的条目数
     */
    public record MapperResult(List<MappingEntry> entries,
                               int mappedClasses,
                               int mappedMethods,
                               int mappedFields,
                               int readableCount) {
    }
}
