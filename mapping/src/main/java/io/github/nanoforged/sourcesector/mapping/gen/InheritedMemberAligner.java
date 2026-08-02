package io.github.nanoforged.sourcesector.mapping.gen;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 继承成员名字对齐器。
 * <p>
 * 动机：scope 片段按类独立翻译，同一逻辑继承成员在「声明类 scope」与「子类 scope」
 * 可能得到不同 named 名（子类侧是 swarm 分配的垃圾名），remap 按 owner 查表导致
 * 引用与声明分叉（运行期 {@code NoSuchMethodError}/{@code NoSuchFieldError}）。
 * {@link InheritedMemberPropagator} 负责为子类补别名，本对齐器负责把子类侧已有条目的
 * remap 目标统一为声明侧目标，并对同 key 重复条目（scope 条目 + 传播别名）去重。
 * <p>
 * 行为：对每条成员条目 (C, obfM → namedN)：
 * <ul>
 *     <li>C 真实声明 obfM（name+desc 精确匹配）→ 声明条目，不动；</li>
 *     <li>C 未声明（继承引用别名）→ 沿 C 的继承链（superclass/接口，仅索引内类）找最近真实声明类 A：
 *         <ul>
 *             <li>表内存在 (A, obfM → namedA) 且目标名不同 → named/intermediary 替换为声明侧目标，记替换日志；</li>
 *             <li>声明类 A 存在但表内无该成员条目 → 保留现状，记 warn（声明侧未翻译，remap 后引用/声明仍可能分叉）；</li>
 *             <li>索引内继承链无任何类声明 obfM → 保留现状，记 warn（声明可能在索引外父类，无法对齐）。</li>
 *         </ul>
 *     </li>
 * </ul>
 * 输出按 (owner, kind, obfName, desc) 去重，保留组内第一条（对齐后组内目标一致）。
 * 生成是确定性的：同一输入两次运行输出字节一致。
 */
public final class InheritedMemberAligner {
    private static final String FIELD = "FIELD";
    private static final String METHOD = "METHOD";

    /**
     * 对齐结果。
     *
     * @param entries      对齐后的全量条目（类块结构保持输入顺序）
     * @param replacements 替换日志（类、成员、旧目标 → 新目标）
     * @param warnings     保留现状的警告（不应大量出现）
     */
    public record AlignmentResult(List<MappingEntry> entries, List<String> replacements, List<String> warnings) {
    }

    private InheritedMemberAligner() {
    }

    /**
     * 对齐合并后全量条目的继承成员名。
     *
     * @param mergedEntries 合并 + 继承传播后的全量条目（三列形态，描述符为 obf 侧 canonical）
     * @param classes       混淆 jar 扫描出的类结构（继承关系与成员声明真值）
     * @return 对齐结果
     */
    public static AlignmentResult align(List<MappingEntry> mergedEntries, List<ClassStructure> classes) {
        Objects.requireNonNull(mergedEntries, "mergedEntries");
        Objects.requireNonNull(classes, "classes");

        Map<String, ClassStructure> structureByName = new HashMap<>();
        Map<String, Set<String>> declaredByClass = new HashMap<>();
        for (ClassStructure classStructure : classes) {
            structureByName.put(classStructure.name(), classStructure);
            Set<String> declared = new HashSet<>();
            for (ClassStructure.Member field : classStructure.fields()) {
                declared.add(kindKey(FIELD, field.name(), field.desc()));
            }
            for (ClassStructure.Member method : classStructure.methods()) {
                declared.add(kindKey(METHOD, method.name(), method.desc()));
            }
            declaredByClass.put(classStructure.name(), declared);
        }

        Map<String, List<MappingEntry>> entriesByKey = new HashMap<>();
        for (MappingEntry entry : mergedEntries) {
            if (entry.isClass()) {
                continue;
            }
            entriesByKey.computeIfAbsent(memberKey(entry), key -> new ArrayList<>()).add(entry);
        }

        List<MappingEntry> aligned = new ArrayList<>(mergedEntries.size());
        List<String> replacements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (MappingEntry entry : mergedEntries) {
            if (entry.isClass()) {
                aligned.add(entry);
                continue;
            }
            String key = memberKey(entry);
            if (!seenKeys.add(key)) {
                // 同 key 重复条目（scope 条目 + 传播别名）：对齐后目标一致，保留组内第一条。
                continue;
            }
            String kindLabel = entry.isField() ? "字段" : "方法";
            if (declaredByClass.getOrDefault(entry.ownerObfuscatedName(), Set.of())
                    .contains(kindKey(entry))) {
                // 声明条目（owner 真实声明该成员）：权威，不动。
                aligned.add(entry);
                continue;
            }

            String declaringClass = nearestDeclaringClass(entry, structureByName, declaredByClass);
            if (declaringClass == null) {
                warnings.add("类 " + entry.ownerObfuscatedName() + " " + kindLabel + " "
                        + entry.obfuscatedName() + entry.descriptor() + ": 索引内继承链无声明类"
                        + "（声明可能在索引外父类），保留现状");
                aligned.add(entry);
                continue;
            }
            List<MappingEntry> declaringEntries = entriesByKey.get(memberKeyOf(declaringClass, entry));
            if (declaringEntries == null || declaringEntries.isEmpty()) {
                warnings.add("类 " + entry.ownerObfuscatedName() + " " + kindLabel + " "
                        + entry.obfuscatedName() + entry.descriptor() + ": 声明类 " + declaringClass
                        + " 表内无该成员条目，保留现状");
                aligned.add(entry);
                continue;
            }
            // 声明侧条目：named 非空优先（未命名条目落 intermediary 占位名，需一致对齐）。
            MappingEntry declaringEntry = declaringEntries.stream()
                    .filter(candidate -> candidate.namedName() != null)
                    .findFirst()
                    .orElse(declaringEntries.get(0));
            if (!declaringEntry.namedOrIntermediary().equals(entry.namedOrIntermediary())) {
                replacements.add("类 " + entry.ownerObfuscatedName() + " " + kindLabel + " "
                        + entry.obfuscatedName() + entry.descriptor() + ": "
                        + entry.namedOrIntermediary() + " -> " + declaringEntry.namedOrIntermediary());
                entry = entry.withNamedName(declaringEntry.namedName())
                        .withIntermediaryName(declaringEntry.intermediaryName());
            }
            aligned.add(entry);
        }
        return new AlignmentResult(List.copyOf(aligned), List.copyOf(replacements), List.copyOf(warnings));
    }

    /**
     * 沿继承链找最近真实声明目标成员（kind + name + desc 精确匹配）的类。
     * <p>
     * 从条目的 owner 类开始，BFS 遍历 superclass 与接口（仅索引内类可展开；
     * 索引外类无声明信息且无表条目，其分支停止向上）。
     *
     * @param entry           目标成员条目
     * @param structureByName 类结构索引
     * @param declaredByClass 类 → 声明成员键集合
     * @return 最近声明类；索引内继承链无声明时返回 {@code null}
     */
    private static String nearestDeclaringClass(MappingEntry entry,
                                                Map<String, ClassStructure> structureByName,
                                                Map<String, Set<String>> declaredByClass) {
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        ClassStructure start = structureByName.get(entry.ownerObfuscatedName());
        if (start != null) {
            if (start.superName() != null) {
                pending.add(start.superName());
            }
            pending.addAll(start.interfaces());
        }
        String kind = entry.isField() ? FIELD : METHOD;
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (declaredByClass.getOrDefault(current, Set.of())
                    .contains(kindKey(kind, entry.obfuscatedName(), entry.descriptor()))) {
                return current;
            }
            ClassStructure structure = structureByName.get(current);
            if (structure == null) {
                continue;
            }
            if (structure.superName() != null) {
                pending.add(structure.superName());
            }
            pending.addAll(structure.interfaces());
        }
        return null;
    }

    private static String memberKey(MappingEntry entry) {
        return memberKeyOf(entry.ownerObfuscatedName(), entry);
    }

    private static String memberKeyOf(String owner, MappingEntry entry) {
        return owner + '#' + entry.kind() + '#' + entry.obfuscatedName() + '#' + entry.descriptor();
    }

    private static String kindKey(MappingEntry entry) {
        return (entry.isField() ? FIELD : METHOD) + '#' + entry.obfuscatedName() + '#' + entry.descriptor();
    }

    private static String kindKey(String kind, String name, String desc) {
        return kind + '#' + name + '#' + desc;
    }
}
