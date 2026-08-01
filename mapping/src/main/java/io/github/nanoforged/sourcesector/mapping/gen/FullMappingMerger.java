package io.github.nanoforged.sourcesector.mapping.gen;

import io.github.nanoforged.sourcesector.mapping.MappingEntry;
import io.github.nanoforged.sourcesector.mapping.MappingLookupException;
import io.github.nanoforged.sourcesector.mapping.MappingTableExporter;
import io.github.nanoforged.sourcesector.mapping.TinyV2MappingRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 全量映射表合并器。
 * <p>
 * 输入人工映射条目（运行期权威表）、scope 语义片段条目与
 * {@link IntermediaryNameGenerator} 生成的中间名条目，输出构建期全量表条目。
 * 分层优先级：占位生成 &lt; scope 片段 &lt; 人工条目（同混淆类/成员高层胜出），
 * 人工与 scope 条目附带的注释原样保留，占位条目无注释。
 * <p>
 * 输出条目按混淆类名排序，类块内先人工成员（保持人工表顺序）、再 scope 成员
 * （保持片段顺序）、后占位成员（保持 jar 声明顺序），保证同一输入两次合并输出字节一致。
 * 全量表为三列形态（obf / intermediary / named），描述符统一以 obf 侧为 canonical：
 * 人工/scope 条目的 named 描述符在合并时换算为 obf 形式，生成条目描述符本即 obf 侧原样。
 * 表外类（JDK / 第三方 / 未混淆的 starfarer.api）在描述符换算中保持原样。
 * <p>
 * 人工/scope 类条目胜出时，生成层发放的 intermediary 类条目只贡献锚点名——
 * 合并器把 intermediary 名转移到胜出条目上（人工层输入无锚点，其未命名成员的
 * intermediary 索引需要 owner 中间名才能解析）。
 */
public final class FullMappingMerger {
    /**
     * 合并人工条目与占位条目为全量表条目（无 scope 片段）。
     *
     * @param humanEntries     人工映射条目（优先）
     * @param generatedEntries 生成的占位条目
     * @return 全量表条目（排序确定）
     */
    public List<MappingEntry> merge(List<MappingEntry> humanEntries, List<MappingEntry> generatedEntries) {
        return merge(humanEntries, List.of(), generatedEntries);
    }

    /**
     * 合并人工条目、scope 片段条目与占位条目为全量表条目。
     *
     * @param humanEntries     人工映射条目（最高优先级）
     * @param scopeEntries     scope 语义片段条目（覆盖占位名，低于人工条目）
     * @param generatedEntries 生成的占位条目
     * @return 全量表条目（排序确定）
     */
    public List<MappingEntry> merge(List<MappingEntry> humanEntries,
                                    List<MappingEntry> scopeEntries,
                                    List<MappingEntry> generatedEntries) {
        Objects.requireNonNull(humanEntries, "humanEntries");
        Objects.requireNonNull(scopeEntries, "scopeEntries");
        Objects.requireNonNull(generatedEntries, "generatedEntries");

        Map<String, MappingEntry> classByObfuscated = new LinkedHashMap<>();
        Map<String, String> namedClassOwner = new HashMap<>();
        Map<String, List<MappingEntry>> humanMembersByOwner = new LinkedHashMap<>();
        Map<String, List<MappingEntry>> scopeMembersByOwner = new LinkedHashMap<>();
        Map<String, List<MappingEntry>> generatedMembersByOwner = new LinkedHashMap<>();

        // scope 层先登记，人工层随后覆盖同混淆 key 的类条目。
        for (MappingEntry entry : scopeEntries) {
            if (entry.isClass()) {
                classByObfuscated.put(entry.obfuscatedName(), entry);
                namedClassOwner.put(entry.namedName(), entry.obfuscatedName());
            } else {
                scopeMembersByOwner.computeIfAbsent(entry.ownerObfuscatedName(), key -> new ArrayList<>()).add(entry);
            }
        }
        for (MappingEntry entry : humanEntries) {
            if (entry.isClass()) {
                MappingEntry displaced = classByObfuscated.put(entry.obfuscatedName(), entry);
                if (displaced != null) {
                    namedClassOwner.remove(displaced.namedName(), entry.obfuscatedName());
                }
                String existingOwner = namedClassOwner.putIfAbsent(entry.namedName(), entry.obfuscatedName());
                if (existingOwner != null && !existingOwner.equals(entry.obfuscatedName())) {
                    throw new MappingLookupException("全量表 named 类名冲突: " + entry.namedName()
                            + " 同时映射 " + existingOwner + " 与 " + entry.obfuscatedName());
                }
            } else {
                humanMembersByOwner.computeIfAbsent(entry.ownerObfuscatedName(), key -> new ArrayList<>()).add(entry);
            }
        }

        Map<String, MappingEntry> generatedClassByObfuscated = new LinkedHashMap<>();
        for (MappingEntry entry : generatedEntries) {
            if (entry.isClass()) {
                generatedClassByObfuscated.put(entry.obfuscatedName(), entry);
            } else {
                generatedMembersByOwner.computeIfAbsent(entry.ownerObfuscatedName(), key -> new ArrayList<>()).add(entry);
            }
        }

        // 人工/scope 优先：生成器为全部非 identity 类发放了 intermediary 类条目，
        // 这里做防御性去重、named 唯一性校验，并把锚点名转移到胜出条目上。
        for (MappingEntry generatedClass : generatedClassByObfuscated.values()) {
            MappingEntry winner = classByObfuscated.get(generatedClass.obfuscatedName());
            if (winner != null) {
                if (winner.intermediaryName() == null) {
                    classByObfuscated.put(winner.obfuscatedName(),
                            winner.withIntermediaryName(generatedClass.intermediaryName()));
                }
                continue;
            }
            if (generatedClass.namedName() != null) {
                String existingOwner = namedClassOwner.putIfAbsent(generatedClass.namedName(), generatedClass.obfuscatedName());
                if (existingOwner != null) {
                    throw new MappingLookupException("全量表 named 类名冲突: " + generatedClass.namedName()
                            + " 同时映射 " + existingOwner + " 与 " + generatedClass.obfuscatedName());
                }
            }
            classByObfuscated.put(generatedClass.obfuscatedName(), generatedClass);
        }

        // 成员去重 key 统一换算为混淆形式描述符：人工/scope 条目存 named 描述符，
        // 占位条目存混淆描述符，只有换算到同一侧才能正确判重。
        // 全量表描述符以 obf 侧为 canonical，故只对非空 named 类登记换算表。
        Map<String, String> namedToObfuscated = new HashMap<>();
        classByObfuscated.forEach((obfuscatedName, classEntry) -> {
            if (classEntry.namedName() != null) {
                namedToObfuscated.put(classEntry.namedName(), obfuscatedName);
            }
        });
        Set<String> humanMemberKeys = memberKeys(humanMembersByOwner, namedToObfuscated);
        Set<String> scopeMemberKeys = memberKeys(scopeMembersByOwner, namedToObfuscated);

        Set<String> allClassNames = new TreeSet<>(classByObfuscated.keySet());
        List<MappingEntry> merged = new ArrayList<>();
        for (String className : allClassNames) {
            merged.add(classByObfuscated.get(className));
            // 人工/scope 成员条目的描述符在此统一换算为 obf 侧 canonical 存储；
            // 生成条目描述符本即 obf 侧，原样收录。
            for (MappingEntry humanMember : humanMembersByOwner.getOrDefault(className, List.of())) {
                merged.add(humanMember.withDescriptor(toObfuscatedDescriptor(humanMember.descriptor(), namedToObfuscated)));
            }
            for (MappingEntry scopeMember : scopeMembersByOwner.getOrDefault(className, List.of())) {
                if (humanMemberKeys.contains(memberKey(scopeMember, namedToObfuscated))) {
                    continue;
                }
                merged.add(scopeMember.withDescriptor(toObfuscatedDescriptor(scopeMember.descriptor(), namedToObfuscated)));
            }
            for (MappingEntry generatedMember : generatedMembersByOwner.getOrDefault(className, List.of())) {
                if (humanMemberKeys.contains(memberKey(generatedMember, namedToObfuscated))
                        || scopeMemberKeys.contains(memberKey(generatedMember, namedToObfuscated))) {
                    continue;
                }
                merged.add(generatedMember);
            }
        }
        return merged;
    }

    /**
     * 用导出器把全量表条目序列化为 Tiny v2 文本。
     *
     * @param mergedEntries 合并后的全量条目
     * @return Tiny v2 文本
     */
    public String exportTiny(List<MappingEntry> mergedEntries) {
        return new MappingTableExporter(TinyV2MappingRepository.of(mergedEntries)).exportTiny();
    }

    /**
     * 计算漂移报告条目：人工映射在 jar 当前结构中找不到对应类/成员的条目列表。
     *
     * @param humanEntries 人工映射条目
     * @param classes      jar 扫描出的类结构
     * @return 漂移描述行（无漂移时为空列表）
     */
    public static List<String> driftLines(List<MappingEntry> humanEntries, List<ClassStructure> classes) {
        return driftLines(humanEntries, classes, Map.of());
    }

    /**
     * 计算漂移报告条目，支持补充 named→obf 类名上下文。
     * <p>
     * scope 片段等外部表的成员描述符可能引用其他表（人工表 / 其他 scope / 占位名）
     * 中的 named 类，逐表校验时需要通过 {@code namedToObfuscatedContext} 提供完整的
     * named→obf 换算上下文（通常取合并后全量表的类条目），否则表外 named 引用会被
     * 误判为成员缺失。条目自身的类映射优先于上下文。
     *
     * @param entries                  待校验的映射条目
     * @param classes                  jar 扫描出的类结构
     * @param namedToObfuscatedContext 补充的 named→obf 类名上下文
     * @return 漂移描述行（无漂移时为空列表）
     */
    public static List<String> driftLines(List<MappingEntry> entries,
                                          List<ClassStructure> classes,
                                          Map<String, String> namedToObfuscatedContext) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(classes, "classes");
        Objects.requireNonNull(namedToObfuscatedContext, "namedToObfuscatedContext");

        Map<String, ClassStructure> classByName = new HashMap<>();
        for (ClassStructure classStructure : classes) {
            classByName.put(classStructure.name(), classStructure);
        }
        Map<String, String> namedToObfuscated = new HashMap<>(namedToObfuscatedContext);
        for (MappingEntry entry : entries) {
            if (entry.isClass()) {
                namedToObfuscated.put(entry.namedName(), entry.obfuscatedName());
            }
        }

        List<String> drift = new ArrayList<>();
        for (MappingEntry entry : entries) {
            if (entry.isClass()) {
                if (!classByName.containsKey(entry.obfuscatedName())) {
                    drift.add("类缺失: " + entry.namedName() + " (表中混淆类名: " + entry.obfuscatedName() + ")");
                }
                continue;
            }

            ClassStructure owner = classByName.get(entry.ownerObfuscatedName());
            if (owner == null) {
                drift.add("owner 类缺失: " + entry.ownerNamedName() + '#' + entry.namedName()
                        + " (表中混淆 owner: " + entry.ownerObfuscatedName() + ")");
                continue;
            }
            String obfuscatedDescriptor = toObfuscatedDescriptor(entry.descriptor(), namedToObfuscated);
            String expected = entry.obfuscatedName() + ':' + obfuscatedDescriptor;
            boolean found = entry.isField()
                    ? owner.fields().stream().anyMatch(field -> (field.name() + ':' + field.desc()).equals(expected))
                    : owner.methods().stream().anyMatch(method -> (method.name() + ':' + method.desc()).equals(expected));
            if (!found) {
                drift.add((entry.isField() ? "字段缺失: " : "方法缺失: ")
                        + entry.ownerNamedName() + '#' + entry.namedName()
                        + " (表中混淆成员: " + entry.obfuscatedName() + ", 换算后描述符: " + obfuscatedDescriptor + ")");
            }
        }
        return drift;
    }

    private static String toObfuscatedDescriptor(String descriptor, Map<String, String> namedToObfuscated) {
        if (descriptor == null || descriptor.indexOf('L') < 0) {
            return descriptor;
        }
        StringBuilder builder = new StringBuilder(descriptor.length());
        int cursor = 0;
        while (cursor < descriptor.length()) {
            char current = descriptor.charAt(cursor);
            if (current != 'L') {
                builder.append(current);
                cursor++;
                continue;
            }
            int end = descriptor.indexOf(';', cursor);
            if (end < 0) {
                throw new MappingLookupException("描述符格式不正确: " + descriptor);
            }
            String internalName = descriptor.substring(cursor + 1, end);
            builder.append('L').append(namedToObfuscated.getOrDefault(internalName, internalName)).append(';');
            cursor = end + 1;
        }
        return builder.toString();
    }

    private static Set<String> memberKeys(Map<String, List<MappingEntry>> membersByOwner,
                                          Map<String, String> namedToObfuscated) {
        Set<String> keys = new HashSet<>();
        for (List<MappingEntry> members : membersByOwner.values()) {
            for (MappingEntry member : members) {
                keys.add(memberKey(member, namedToObfuscated));
            }
        }
        return keys;
    }

    private static String memberKey(MappingEntry entry, Map<String, String> namedToObfuscated) {
        return entry.ownerObfuscatedName() + '#' + entry.kind() + '#' + entry.obfuscatedName()
                + '#' + toObfuscatedDescriptor(entry.descriptor(), namedToObfuscated);
    }
}
