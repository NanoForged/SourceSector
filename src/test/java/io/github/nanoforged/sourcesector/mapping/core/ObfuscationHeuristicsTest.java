package io.github.nanoforged.sourcesector.mapping.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObfuscationHeuristics} 测试：可读名判定规则（移植自 legacy 提升判定）。
 */
class ObfuscationHeuristicsTest {

    private final ObfuscationHeuristics heuristics = new ObfuscationHeuristics();

    @Test
    void originalDeveloperNamesJudgedReadable() {
        assertTrue(heuristics.isReadableMemberName("ship"));
        assertTrue(heuristics.isReadableMemberName("render"));
        assertTrue(heuristics.isReadableMemberName("MAX_RANGE"));
        assertTrue(heuristics.isReadableMemberName("onClick"));
        assertTrue(heuristics.isReadableMemberName("fooBar1"));
    }

    @Test
    void obfuscatorNamesJudgedUnreadable() {
        assertFalse(heuristics.isReadableMemberName("a"));
        assertFalse(heuristics.isReadableMemberName("ab"));
        assertFalse(heuristics.isReadableMemberName("a1"));
        assertFalse(heuristics.isReadableMemberName("oOO"));
        assertFalse(heuristics.isReadableMemberName("0000"));
        assertFalse(heuristics.isReadableMemberName("ab0000cd"));
    }

    @Test
    void compilerSyntheticAndInvalidIdentifiersUnreadable() {
        assertFalse(heuristics.isReadableMemberName("this$0"));
        assertFalse(heuristics.isReadableMemberName("$SWITCH_TABLE$a"));
        assertFalse(heuristics.isReadableMemberName("foo-bar"));
        assertFalse(heuristics.isReadableMemberName("foo bar"));
    }

    @Test
    void obfuscatorDictionaryReservedNamesUnreadable() {
        assertFalse(heuristics.isReadableMemberName("String"));
        assertFalse(heuristics.isReadableMemberName("Object"));
        assertFalse(heuristics.isReadableMemberName("for"));
        assertFalse(heuristics.isReadableMemberName("class"));
        assertFalse(heuristics.isReadableMemberName("true"));
    }

    @Test
    void dictionaryWordConcatenationUnreadable() {
        // Allatori/yGuard 关键字拼接名：由多个字典词组成，不是原始命名。
        assertFalse(heuristics.isReadableMemberName("returnsuper"));
        assertFalse(heuristics.isReadableMemberName("intsuper"));
        assertFalse(heuristics.isReadableMemberName("nullsuper"));
        assertFalse(heuristics.isReadableMemberName("newthrow"));
        assertFalse(heuristics.isReadableMemberName("forwhile"));
        // 全小写拼接形态（yGuard：stringsuper = string+super）。
        assertFalse(heuristics.isReadableMemberName("stringsuper"));
        assertFalse(heuristics.isReadableMemberName("ifsuper"));
        assertFalse(heuristics.isReadableMemberName("thisvoid"));
    }

    @Test
    void realNamesNotAffectedByDictionaryConcatenation() {
        // 首字母大写段（Move/Point）或非字典段（ship/opad）不在字典 → 保持可读。
        assertTrue(heuristics.isReadableMemberName("MoveToPointManeuverV2"));
        assertTrue(heuristics.isReadableMemberName("opad"));
        assertTrue(heuristics.isReadableMemberName("MAX_RANGE"));
        assertTrue(heuristics.isReadableMemberName("FleetInFormationDisplay"));
        // getter 风格（is 不在字典，避免 isTrue/isValid 误伤）。
        assertTrue(heuristics.isReadableMemberName("isTrue"));
        assertTrue(heuristics.isReadableMemberName("isValid"));
    }

    @Test
    void classNameJudgedBySimpleName() {
        assertTrue(heuristics.isReadableClassName("com/example/MyShip"));
        assertTrue(heuristics.isReadableClassName("com/example/GameUI"));
        assertFalse(heuristics.isReadableClassName("com/fs/graphics/L"));
        assertFalse(heuristics.isReadableClassName("com/example/UI"));
        assertFalse(heuristics.isReadableClassName("a/b/C$1"));
        assertFalse(heuristics.isReadableClassName("x"));
    }
}
