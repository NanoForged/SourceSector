package io.github.nanoforged.sourcesector.util;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.mapping.core.MappingGenerator;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.EmptyElementFilter;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitOrder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * mapping-io 树层统一入口：读取、构建、合并与写出全部基于
 * {@link MemoryMappingTree} 与官方 adapter，内部不再维护自建映射表/投影。
 * <ul>
 *   <li>{@link #read} —— {@link MappingReader} 直读 Tiny v2 为树；</li>
 *   <li>{@link #fromEntries} —— 程序化 visit 驱动构建（保持条目插入序，
 *       生成器拓扑序原样保留）；</li>
 *   <li>{@link #mergeInto} —— overlay 类逐个 {@code addClass} 合并（同名类
 *       dst 覆盖、成员按 (src, desc) 合并、缺失条目插入——mapping-io 的
 *       {@code copyFrom(replace=true)} 语义，等同分层映射合并）；</li>
 *   <li>{@link #write} / {@link #writeProjection} —— {@code accept} 到
 *       {@link Tiny2FileWriter}（escapeNames=true，与既有产物字节兼容）；
 *       投影链 = {@link MappingSourceNsSwitch}（切 src 命名空间并自动换算
 *       描述符类名）+ {@link MappingDstNsReorder}（裁剪目标列）+
 *       {@link EmptyElementFilter}（滤除未映射元素）。</li>
 * </ul>
 * 映射名的确定性由生成器（{@link MappingGenerator}）保证，与文件行序无关。
 */
public final class MappingTreeUtil {

    private MappingTreeUtil() {
    }

    /**
     * 读取 Tiny v2 映射文件为树。
     *
     * @param file 映射文件
     * @return 内存树（命名空间布局取自文件头）
     * @throws IOException 读取或解析失败
     */
    public static MemoryMappingTree read(Path file) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(file, MappingFormat.TINY_2_FILE, tree);
        return tree;
    }

    /**
     * 从条目列表构建树（生成器产物 → 树，程序化 visit 驱动，插入序保持）。
     * <p>
     * dst 名按命名空间名从条目取列（{@code named} / {@code intermediary}），
     * null 表示该命名空间未映射。
     *
     * @param entries        条目（拓扑序）
     * @param srcNamespace   源命名空间名
     * @param dstNamespaces  目标命名空间名列表
     * @return 内存树
     */
    public static MemoryMappingTree fromEntries(List<MappingEntry> entries,
                                                String srcNamespace,
                                                List<String> dstNamespaces) {
        MemoryMappingTree tree = new MemoryMappingTree();
        try {
            tree.visitNamespaces(srcNamespace, dstNamespaces);
            for (MappingEntry entry : entries) {
                if (entry.isClass()) {
                    tree.visitClass(entry.obfuscatedName());
                    visitDstNames(tree, MappedElementKind.CLASS, entry, dstNamespaces);
                    tree.visitElementContent(MappedElementKind.CLASS);
                } else {
                    MappedElementKind kind = entry.isField() ? MappedElementKind.FIELD : MappedElementKind.METHOD;
                    if (entry.isField()) {
                        tree.visitField(entry.obfuscatedName(), entry.descriptor());
                    } else {
                        tree.visitMethod(entry.obfuscatedName(), entry.descriptor());
                    }
                    visitDstNames(tree, kind, entry, dstNamespaces);
                    tree.visitElementContent(kind);
                }
            }
            tree.visitEnd();
        } catch (IOException e) {
            // 纯内存驱动不会实际触发 IO 异常。
            throw new IllegalStateException("MemoryMappingTree Building Failed", e);
        }
        return tree;
    }

    /**
     * 分层合并：overlay 的类全部合并进 base（mapping-io {@code addClass} 语义——
     * 同名类 dst 按命名空间覆盖、null 不覆盖、成员按 (src 名, 描述符) 合并、
     * 缺失类/成员插入；插入序 = 独有条目追加到末尾）。
     *
     * @param base    低层树（被修改）
     * @param overlay 高层树（只读）
     */
    public static void mergeInto(MemoryMappingTree base, MemoryMappingTree overlay) {
        for (ClassMapping cls : overlay.getClasses()) {
            base.addClass(cls);
        }
    }

    /**
     * 直出：按树内顺序与全部命名空间写出（escapeNames=true）。
     *
     * @param out  输出路径
     * @param tree 内存树
     * @throws IOException 写出失败
     */
    public static void write(Path out, MemoryMappingTree tree) throws IOException {
        write(out, tree, VisitOrder.createByInputOrder());
    }

    /**
     * 直出：按指定访问顺序与全部命名空间写出（escapeNames=true）。
     * <p>
     * 默认 {@link VisitOrder#createByInputOrder()} 保持树内插入序；外部来源
     * （如 Enigma 目录递归扫描）顺序不定时，可传
     * {@link VisitOrder#createByName()} 获得确定性排序输出。
     *
     * @param out   输出路径
     * @param tree  内存树
     * @param order 访问顺序
     * @throws IOException 写出失败
     */
    public static void write(Path out, MemoryMappingTree tree, VisitOrder order) throws IOException {
        try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
             Tiny2FileWriter tinyWriter = new Tiny2FileWriter(writer, true)) {
            tree.accept(tinyWriter, order);
        }
    }

    /**
     * 投影写出：可选切换源命名空间（描述符类名自动换算，如 obf→intermediary
     * 段的 desc 重映射）、裁剪目标列、滤除未映射元素。
     *
     * @param out             输出路径
     * @param tree            内存树
     * @param newSrcNamespace 新源命名空间名；{@code null} 保持原 src（不切换）
     * @param dstNamespaces   保留的目标命名空间（省略即丢弃；列表顺序即输出列序）
     * @param omitEmpty       是否滤除目标命名空间全部未映射的元素（空列元素整行省略）
     * @throws IOException 写出失败
     */
    public static void writeProjection(Path out,
                                       MemoryMappingTree tree,
                                       String newSrcNamespace,
                                       List<String> dstNamespaces,
                                       boolean omitEmpty) throws IOException {
        try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
             Tiny2FileWriter tinyWriter = new Tiny2FileWriter(writer, true)) {
            // 链序：切 src（desc 换算）→ 裁剪目标列 → 滤空。EmptyElementFilter 必须在
            // MappingDstNsReorder 之后：SourceNsSwitch 第二 pass 会把原 src 名写入新
            // dst 列（obf 视角），Filter 在裁剪前会误判该列非空而放过空元素。
            MappingVisitor chain = tinyWriter;
            if (omitEmpty) {
                chain = new EmptyElementFilter(chain);
            }
            chain = new MappingDstNsReorder(chain, dstNamespaces);
            if (newSrcNamespace != null) {
                chain = new MappingSourceNsSwitch(chain, newSrcNamespace);
            }
            tree.accept(chain, VisitOrder.createByInputOrder());
        }
    }

    /** 按命名空间名把条目目标列写入树（null 不写 = 未映射）。 */
    private static void visitDstNames(MemoryMappingTree tree,
                                      MappedElementKind kind,
                                      MappingEntry entry,
                                      List<String> dstNamespaces) {
        for (int i = 0; i < dstNamespaces.size(); i++) {
            String name = switch (dstNamespaces.get(i)) {
                case "named" -> entry.namedName();
                case "intermediary" -> entry.intermediaryName();
                default -> throw new IllegalArgumentException(
                        "条目目标列仅支持 named/intermediary，实际: " + dstNamespaces.get(i));
            };
            if (name != null) {
                tree.visitDstName(kind, i, name);
            }
        }
    }
}
