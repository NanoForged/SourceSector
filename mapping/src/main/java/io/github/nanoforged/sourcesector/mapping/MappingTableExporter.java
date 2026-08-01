package io.github.nanoforged.sourcesector.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 映射表导出器。
 * <p>
 * 将仓库中的 Tiny v2 数据转成 markdown、CSV 以及控制台表格，便于审查与维护。
 */
public final class MappingTableExporter {
    private final MappingRepository repository;

    /**
     * 创建导出器。
     *
     * @param repository 映射仓库
     */
    public MappingTableExporter(MappingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 导出 markdown 表格。
     *
     * @return markdown 文本
     */
    public String exportMarkdown() {
        StringBuilder builder = new StringBuilder();
        builder.append("# SSOptimizer Mapping Table\n\n");
        builder.append("| kind | obfuscated | named | descriptor |\n");
        builder.append("|---|---|---|---|\n");
        for (Row row : rows()) {
            builder.append("| ")
                    .append(row.kind)
                    .append(" | ")
                    .append(row.obfuscated)
                    .append(" | ")
                    .append(row.named)
                    .append(row.descriptor.isEmpty() ? " | |\n" : " | ")
                    .append(row.descriptor)
                    .append(row.descriptor.isEmpty() ? "" : " |\n");
        }
        return builder.toString();
    }

    /**
     * 导出 CSV 表格。
     *
     * @return CSV 文本
     */
    public String exportCsv() {
        StringBuilder builder = new StringBuilder();
        builder.append("kind,obfuscated,named,descriptor\n");
        for (Row row : rows()) {
            builder.append(row.kind).append(',')
                    .append(row.obfuscated).append(',')
                    .append(row.named).append(',')
                    .append(row.descriptor)
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * 导出固定宽度的控制台表格。
     *
     * @return 对齐后的文本表格
     */
    public String exportTable() {
        List<Row> rows = rows();
        int kindWidth = Math.max("kind".length(), rows.stream().mapToInt(row -> row.kind.length()).max().orElse(0));
        int obfuscatedWidth = Math.max("obfuscated".length(), rows.stream().mapToInt(row -> row.obfuscated.length()).max().orElse(0));
        int namedWidth = Math.max("named".length(), rows.stream().mapToInt(row -> row.named.length()).max().orElse(0));

        StringBuilder builder = new StringBuilder();
        builder.append("SSOptimizer Mapping Table\n");
        builder.append(String.format(Locale.ROOT, "%-" + kindWidth + "s %-" + obfuscatedWidth + "s %-" + namedWidth + "s %s\n",
                "kind", "obfuscated", "named", "descriptor"));
        for (Row row : rows) {
            if (row.descriptor.isEmpty()) {
                builder.append(String.format(Locale.ROOT, "%-" + kindWidth + "s %-" + obfuscatedWidth + "s %-" + namedWidth + "s\n",
                        row.kind, row.obfuscated, row.named));
            } else {
                builder.append(String.format(Locale.ROOT, "%-" + kindWidth + "s %-" + obfuscatedWidth + "s %-" + namedWidth + "s %s\n",
                        row.kind, row.obfuscated, row.named, row.descriptor));
            }
        }
        return builder.toString();
    }

    /**
     * 导出 Tiny v2 映射文本。
     * <p>
     * 按条目原始顺序输出类行与成员行，并保留注释行（类行下 {@code \tc}、成员行下
     * {@code \t\tc}），使"解析 → 导出 → 再解析"往返不丢失注释。
     * <p>
     * 列布局由条目内容决定：任一条目带 intermediary 名时输出三列
     * {@code obf intermediary named}（unnamed 条目省略 named 尾列），
     * 否则输出双列 {@code obf named}（人工层输入格式）。
     *
     * @return Tiny v2 文本
     */
    public String exportTiny() {
        boolean threeColumn = repository.entries().stream().anyMatch(entry -> entry.intermediaryName() != null);

        StringBuilder builder = new StringBuilder();
        builder.append(threeColumn ? "tiny\t2\t0\tobf\tintermediary\tnamed\n" : "tiny\t2\t0\tobf\tnamed\n");
        for (MappingEntry entry : repository.entries()) {
            if (entry.isClass()) {
                builder.append("c\t").append(entry.obfuscatedName());
                if (threeColumn) {
                    builder.append('\t').append(intermediaryColumn(entry));
                    if (entry.namedName() != null) {
                        builder.append('\t').append(entry.namedName());
                    }
                } else {
                    builder.append('\t').append(entry.namedName());
                }
                builder.append('\n');
                appendComment(builder, "\t", entry);
                continue;
            }
            builder.append('\t').append(entry.isField() ? 'f' : 'm')
                    .append('\t').append(entry.obfuscatedName());
            if (threeColumn) {
                builder.append('\t').append(intermediaryColumn(entry));
                if (entry.namedName() != null) {
                    builder.append('\t').append(entry.namedName());
                }
            } else {
                builder.append('\t').append(entry.namedName());
            }
            builder.append('\t').append(entry.descriptor()).append('\n');
            appendComment(builder, "\t\t", entry);
        }
        return builder.toString();
    }

    /**
     * 三列模式下 intermediary 列取值：人工/identity 层条目无锚点名（{@code null}），
     * 按标准 tiny 语义（该命名空间未映射 = 保持源名）写 obf 名充当 identity intermediary。
     * 生成层条目必有真实指纹锚点名，不经过此分支。
     */
    private static String intermediaryColumn(MappingEntry entry) {
        return entry.intermediaryName() != null ? entry.intermediaryName() : entry.obfuscatedName();
    }

    private static void appendComment(StringBuilder builder, String indent, MappingEntry entry) {
        if (entry.comment() == null) {
            return;
        }
        for (String commentLine : entry.comment().split("\n", -1)) {
            builder.append(indent).append('c').append('\t').append(commentLine).append('\n');
        }
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (MappingEntry entry : repository.entries()) {
            rows.add(new Row(
                    entry.kind().name().toLowerCase(Locale.ROOT),
                    toObfuscatedText(entry),
                    toNamedText(entry),
                    entry.descriptor() == null ? "" : entry.descriptor()));
        }
        return rows;
    }

    private static String toObfuscatedText(MappingEntry entry) {
        if (entry.isClass()) {
            return entry.obfuscatedName();
        }
        return entry.ownerObfuscatedName() + '#' + entry.obfuscatedName();
    }

    private static String toNamedText(MappingEntry entry) {
        // unnamed 条目展示 intermediary 占位名，保证审查视图不缺失目标名
        return entry.namedOrIntermediary();
    }

    private record Row(String kind, String obfuscated, String named, String descriptor) {
    }
}