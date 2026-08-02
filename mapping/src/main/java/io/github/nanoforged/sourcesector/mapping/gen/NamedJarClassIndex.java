package io.github.nanoforged.sourcesector.mapping.gen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * named 游戏 jar 的类成员索引。
 * <p>
 * 以 remap 产物（4 个游戏 jar：starfarer_obf / starfarer.api / fs.common_obf / fs.sound_obf）
 * 为唯一事实源，为 {@link NamedJarLinkValidator} 提供「类名 → 自身声明的成员 / 父类 / 接口」的
 * 查询入口。只收录索引内类自身声明的成员，不沿继承链展开——链式解析由校验器负责。
 * 复用 {@link ClassStructure} 的结构扫描（不读方法体），与占位名生成共用同一扫描成本。
 */
public final class NamedJarClassIndex {
    /** 单个类在索引内的声明信息（不含继承链展开）。 */
    public record ClassEntry(String name,
                             String superName,
                             List<String> interfaces,
                             Set<String> methodKeys,
                             Set<String> fieldKeys) {
        /**
         * 判断该类是否声明了指定方法（name+desc 精确匹配，含 {@code <init>}/{@code <clinit>}）。
         *
         * @param name 方法名
         * @param desc 方法描述符
         * @return 是否声明
         */
        public boolean declaresMethod(String name, String desc) {
            return methodKeys.contains(name + ":" + desc);
        }

        /**
         * 判断该类是否声明了指定字段（name+desc 精确匹配）。
         *
         * @param name 字段名
         * @param desc 字段描述符
         * @return 是否声明
         */
        public boolean declaresField(String name, String desc) {
            return fieldKeys.contains(name + ":" + desc);
        }
    }

    private final Map<String, ClassEntry> classes;

    private NamedJarClassIndex(Map<String, ClassEntry> classes) {
        this.classes = classes;
    }

    /**
     * 构建索引。
     *
     * @param gameJars 4 个游戏 named jar（只读取 {@code .class} 条目）
     * @return 类名 → 声明信息的索引
     * @throws IOException 若读取失败
     */
    public static NamedJarClassIndex build(List<Path> gameJars) throws IOException {
        Objects.requireNonNull(gameJars, "gameJars");
        Map<String, ClassEntry> classes = new HashMap<>();
        for (ClassStructure structure : ClassStructure.scan(gameJars)) {
            Set<String> methods = new HashSet<>();
            for (ClassStructure.Member member : structure.methods()) {
                methods.add(member.name() + ":" + member.desc());
            }
            Set<String> fields = new HashSet<>();
            for (ClassStructure.Member member : structure.fields()) {
                fields.add(member.name() + ":" + member.desc());
            }
            classes.put(structure.name(), new ClassEntry(
                    structure.name(), structure.superName(), structure.interfaces(), methods, fields));
        }
        return new NamedJarClassIndex(classes);
    }

    /**
     * 查询类声明信息。
     *
     * @param internalName 类内部名
     * @return 类声明信息；类不在索引中时返回 {@code null}
     */
    public ClassEntry entry(String internalName) {
        return classes.get(internalName);
    }

    /**
     * 类是否在索引中。
     *
     * @param internalName 类内部名
     * @return 是否在索引中
     */
    public boolean contains(String internalName) {
        return classes.containsKey(internalName);
    }

    /**
     * 索引内的类总数。
     *
     * @return 类总数
     */
    public int size() {
        return classes.size();
    }
}
