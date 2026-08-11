package io.github.nanoforged.sourcesector.mapping.core;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 混淆名启发式判定：区分"混淆器改写名"与"未混淆的原始可读名"。
 * <p>
 * 规则移植自 {@code IntermediaryNameGenerator}（旧链提升规则） 的提升判定，行为保持一致：
 * 可读条件为长度 ≥ 3 的纯 ASCII Java 标识符、不含 {@code $}（排除编译器合成名）、
 * 不在混淆器字典保留名集合（Java 关键字/字面量/常见 JDK 类型名）、且无 o0 字典垃圾特征。
 * <p>
 * 判定是纯函数：同一名字多次判定结果恒定，不依赖上下文与运行状态——
 * 这是"可读名回写"确定性输出的基础。
 */
public final class ObfuscationHeuristics {

    /**
     * 混淆器字典保留名：Java 关键字、字面量与常见 JDK 类型名。
     * 这些名字是混淆器（Allatori 系）从字典里挑的合法标识符，不是原始命名，不得提升。
     */
    private static final Set<String> OBFUSCATOR_DICTIONARY_NAMES = Set.of(
            // Java 关键字与字面量（named 端必须保持可被 Java 源码引用）
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null",
            // 混淆器字典里的常见 JDK 类型名（合法标识符但明显非原始命名）
            "String", "Object", "Class", "Number", "Integer", "Long", "Float", "Double", "Boolean",
            "Byte", "Short", "Character", "StringBuffer", "StringBuilder", "Void", "System",
            "Runtime", "Math", "Thread", "Runnable", "Iterable", "Comparable", "Enum", "Process",
            "Exception", "RuntimeException", "Error", "Throwable");

    /** 纯 ASCII Java 标识符。 */
    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    /** o0 字典垃圾特征：纯 o/O/0 堆叠。 */
    private static final Pattern O0_DICTIONARY_NAME = Pattern.compile("[oO0]{3,}");

    /** 全小写字典（yGuard 关键字拼接如 stringsuper = string+super 的检测用）。 */
    private static final Set<String> OBFUSCATOR_DICTIONARY_NAMES_LOWER = OBFUSCATOR_DICTIONARY_NAMES.stream().map(String::toLowerCase).collect(Collectors.toSet());


    /**
     * 判定成员名（字段/方法）是否为未被混淆器改写的原始可读名。
     *
     * @param name 混淆 jar 中的成员名
     * @return true 表示该名是原始开发者命名，可作为可读名回写
     */
    public boolean isReadableMemberName(String name) {
        if (name.length() < 3 || name.indexOf('$') >= 0) {
            return false;
        }
        if (OBFUSCATOR_DICTIONARY_NAMES.contains(name)) {
            return false;
        }
        if (!ASCII_IDENTIFIER.matcher(name).matches()) {
            return false;
        }
        if (name.contains("0000") || O0_DICTIONARY_NAME.matcher(name).matches()) {
            return false;
        }
        // 字典词拼接名（yGuard：returnsuper、intsuper、stringsuper 等）：
        // 由多个字典词组成的名字是混淆器字典产物，不是原始命名——原始大小写与
        // 全小写两种形态都检测；首字母大写段不在字典的真实命名（MoveToPointManeuverV2）
        // 与 getter 风格名（isTrue——is 不在字典）不受影响。
        return !isDictionaryWordComposition(name);
    }

    /** 判定名字是否完全由字典词拼接而成（至少两个词，整体不在字典）。 */
    private static boolean isDictionaryWordComposition(String name) {
        return canDecompose(name, OBFUSCATOR_DICTIONARY_NAMES)
                || canDecompose(name.toLowerCase(java.util.Locale.ROOT),
                        OBFUSCATOR_DICTIONARY_NAMES_LOWER);
    }

    /**
     * 判定名串能否被切分为若干字典词（Word-Break 判定）。
     * <p>
     * 动态规划，O(length²)：{@code dp[i]} 表示子串 {@code name[i..]} 可分解；
     * 空后缀视为可分解（与原回溯"到达末尾即成功"语义一致）。
     * 替代原先的指数级回溯实现（长名 + 字典词密集时最坏 O(2^n)）。
     */
    private static boolean canDecompose(String name, Set<String> dictionary) {
        int length = name.length();
        boolean[] dp = new boolean[length + 1];
        dp[length] = length > 0;
        for (int i = length - 1; i >= 0; i--) {
            for (int end = length; end > i; end--) {
                if (dictionary.contains(name.substring(i, end)) && dp[end]) {
                    dp[i] = true;
                    break;
                }
            }
        }
        return dp[0];
    }

    /**
     * 判定类名是否为未被混淆器改写的原始可读名（按类简单名判定）。
     *
     * @param internalName 类内部名（{@code com/fs/graphics/L} 形式）
     * @return true 表示该类的简单名是原始开发者命名
     */
    public boolean isReadableClassName(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        String simpleName = lastSlash < 0 ? internalName : internalName.substring(lastSlash + 1);
        return isReadableMemberName(simpleName);
    }
}
