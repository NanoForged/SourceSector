package io.github.nanoforged.sourcesector.mapping;

import java.util.Objects;

/**
 * 单条 Tiny v2 映射记录。
 * <p>
 * 该类型统一描述类、字段和方法映射，便于查询层和导出层共享同一份事实数据。
 */
public final class MappingEntry {
    /**
     * 映射条目类型。
     */
    public enum Kind {
        /** 类映射。 */
        CLASS,
        /** 字段映射。 */
        FIELD,
        /** 方法映射。 */
        METHOD
    }

    private final Kind kind;
    private final String ownerObfuscatedName;
    private final String ownerNamedName;
    private final String obfuscatedName;
    private final String intermediaryName;
    private final String namedName;
    private final String descriptor;
    private final String comment;

    private MappingEntry(Kind kind,
                         String ownerObfuscatedName,
                         String ownerNamedName,
                         String obfuscatedName,
                         String intermediaryName,
                         String namedName,
                         String descriptor,
                         String comment) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.ownerObfuscatedName = ownerObfuscatedName;
        this.ownerNamedName = ownerNamedName;
        this.obfuscatedName = Objects.requireNonNull(obfuscatedName, "obfuscatedName");
        this.intermediaryName = intermediaryName;
        this.namedName = namedName;
        this.descriptor = descriptor;
        this.comment = comment;
    }

    /**
     * 创建类映射条目（双列人工层：无 intermediary）。
     *
     * @param obfuscatedName 混淆类名
     * @param namedName      可读类名
     * @return 类映射条目
     */
    public static MappingEntry classEntry(String obfuscatedName, String namedName) {
        return new MappingEntry(Kind.CLASS, null, null, obfuscatedName, null, namedName, null, null);
    }

    /**
     * 创建类映射条目（三列全量表）。
     *
     * @param obfuscatedName   混淆类名
     * @param intermediaryName 中间类名（结构指纹占位名）
     * @param namedName        可读类名；未命名条目为 {@code null}
     * @return 类映射条目
     */
    public static MappingEntry classEntry(String obfuscatedName, String intermediaryName, String namedName) {
        return new MappingEntry(Kind.CLASS, null, null, obfuscatedName, intermediaryName, namedName, null, null);
    }

    /**
     * 创建字段映射条目（双列人工层：无 intermediary）。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param ownerNamedName      可读类名
     * @param obfuscatedName      混淆字段名
     * @param namedName           可读字段名
     * @param descriptor          字段描述符
     * @return 字段映射条目
     */
    public static MappingEntry fieldEntry(String ownerObfuscatedName,
                                          String ownerNamedName,
                                          String obfuscatedName,
                                          String namedName,
                                          String descriptor) {
        return new MappingEntry(Kind.FIELD, ownerObfuscatedName, ownerNamedName, obfuscatedName, null, namedName, descriptor, null);
    }

    /**
     * 创建字段映射条目（三列全量表）。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param ownerNamedName      可读类名；未命名条目为 {@code null}
     * @param obfuscatedName      混淆字段名
     * @param intermediaryName    中间字段名
     * @param namedName           可读字段名；未命名条目为 {@code null}
     * @param descriptor          字段描述符（canonical：obf 侧）
     * @return 字段映射条目
     */
    public static MappingEntry fieldEntry(String ownerObfuscatedName,
                                          String ownerNamedName,
                                          String obfuscatedName,
                                          String intermediaryName,
                                          String namedName,
                                          String descriptor) {
        return new MappingEntry(Kind.FIELD, ownerObfuscatedName, ownerNamedName, obfuscatedName, intermediaryName, namedName, descriptor, null);
    }

    /**
     * 创建方法映射条目（双列人工层：无 intermediary）。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param ownerNamedName      可读类名
     * @param obfuscatedName      混淆方法名
     * @param namedName           可读方法名
     * @param descriptor          方法描述符
     * @return 方法映射条目
     */
    public static MappingEntry methodEntry(String ownerObfuscatedName,
                                           String ownerNamedName,
                                           String obfuscatedName,
                                           String namedName,
                                           String descriptor) {
        return new MappingEntry(Kind.METHOD, ownerObfuscatedName, ownerNamedName, obfuscatedName, null, namedName, descriptor, null);
    }

    /**
     * 创建方法映射条目（三列全量表）。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param ownerNamedName      可读类名；未命名条目为 {@code null}
     * @param obfuscatedName      混淆方法名
     * @param intermediaryName    中间方法名
     * @param namedName           可读方法名；未命名条目为 {@code null}
     * @param descriptor          方法描述符（canonical：obf 侧）
     * @return 方法映射条目
     */
    public static MappingEntry methodEntry(String ownerObfuscatedName,
                                           String ownerNamedName,
                                           String obfuscatedName,
                                           String intermediaryName,
                                           String namedName,
                                           String descriptor) {
        return new MappingEntry(Kind.METHOD, ownerObfuscatedName, ownerNamedName, obfuscatedName, intermediaryName, namedName, descriptor, null);
    }

    /**
     * 返回映射条目类型。
     *
     * @return 类型
     */
    public Kind kind() {
        return kind;
    }

    /**
     * 返回混淆侧拥有者类名。
     *
     * @return 混淆侧类名；类条目返回 {@code null}
     */
    public String ownerObfuscatedName() {
        return ownerObfuscatedName;
    }

    /**
     * 返回可读侧拥有者类名。
     *
     * @return 可读侧类名；类条目返回 {@code null}
     */
    public String ownerNamedName() {
        return ownerNamedName;
    }

    /**
     * 返回混淆名称。
     *
     * @return 混淆名称
     */
    public String obfuscatedName() {
        return obfuscatedName;
    }

    /**
     * 返回中间名称（结构指纹占位名，三列全量表条目必有）。
     *
     * @return 中间名称；双列人工层条目返回 {@code null}
     */
    public String intermediaryName() {
        return intermediaryName;
    }

    /**
     * 返回可读名称。
     *
     * @return 可读名称；三列全量表的未命名条目返回 {@code null}
     */
    public String namedName() {
        return namedName;
    }

    /**
     * 返回 remap 目标名：named 若非空，否则 intermediary。
     * <p>
     * obf→named remap 的统一取值规则——未命名条目落到 intermediary 占位名，
     * 与旧双列全量表（占位名写在 named 列）的 remap 结果字节级一致。
     *
     * @return remap 目标名；双列条目等价于 {@link #namedName()}
     */
    public String namedOrIntermediary() {
        return namedName != null ? namedName : intermediaryName;
    }

    /**
     * 返回描述符。
     *
     * @return 描述符；类条目返回 {@code null}
     */
    public String descriptor() {
        return descriptor;
    }

    /**
     * 返回映射注释。
     * <p>
     * 注释来自 Tiny v2 注释行（类行下的 {@code \tc ...}、成员行下的 {@code \t\tc ...}），
     * 是映射维护者记录命名来源与证据的载体，不进入字节码。
     *
     * @return 注释文本；无注释返回 {@code null}
     */
    public String comment() {
        return comment;
    }

    /**
     * 返回附带指定注释的条目副本。
     *
     * @param newComment 注释文本；{@code null} 表示无注释
     * @return 新条目
     */
    public MappingEntry withComment(String newComment) {
        return new MappingEntry(kind, ownerObfuscatedName, ownerNamedName, obfuscatedName, intermediaryName, namedName, descriptor, newComment);
    }

    /**
     * 返回附带指定可读名的条目副本（用于合并层为 unnamed 条目补 named）。
     *
     * @param newNamedName 可读名
     * @return 新条目
     */
    public MappingEntry withNamedName(String newNamedName) {
        return new MappingEntry(kind, ownerObfuscatedName, ownerNamedName, obfuscatedName, intermediaryName, newNamedName, descriptor, comment);
    }

    /**
     * 返回附带指定中间名的条目副本。
     * <p>
     * 合并层用该方法把生成层计算的结构指纹锚点名转移到胜出的人工/scope 类条目上：
     * 人工层输入是双列表（无 intermediary），进全量表时必须补上该锚点，
     * 否则其未命名成员的 intermediary 索引无法解析 owner。
     *
     * @param newIntermediaryName 中间名
     * @return 新条目
     */
    public MappingEntry withIntermediaryName(String newIntermediaryName) {
        return new MappingEntry(kind, ownerObfuscatedName, ownerNamedName, obfuscatedName, newIntermediaryName, namedName, descriptor, comment);
    }

    /**
     * 返回附带指定描述符的条目副本。
     * <p>
     * 全量表描述符以 obf 侧为 canonical，合并层用该方法把人工/scope 条目的
     * named 描述符换算结果写回条目（其余字段与注释保持不变）。
     *
     * @param newDescriptor 描述符
     * @return 新条目
     */
    public MappingEntry withDescriptor(String newDescriptor) {
        return new MappingEntry(kind, ownerObfuscatedName, ownerNamedName, obfuscatedName, intermediaryName, namedName, newDescriptor, comment);
    }

    /**
     * 判断条目是否为类映射。
     *
     * @return 是类映射返回 {@code true}
     */
    public boolean isClass() {
        return kind == Kind.CLASS;
    }

    /**
     * 判断条目是否为字段映射。
     *
     * @return 是字段映射返回 {@code true}
     */
    public boolean isField() {
        return kind == Kind.FIELD;
    }

    /**
     * 判断条目是否为方法映射。
     *
     * @return 是方法映射返回 {@code true}
     */
    public boolean isMethod() {
        return kind == Kind.METHOD;
    }
}