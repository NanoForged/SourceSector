package io.github.nanoforged.sourcesector.mapping;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tiny v2 映射仓库的读取行为测试。
 * <p>
 * 该测试先定义映射文件加载、类名查询与缺失映射错误信息的契约，确保后续实现
 * 以 Tiny v2 作为唯一事实来源，并且对外提供可读的失败信息。
 */
class TinyV2MappingRepositoryTest {

    @Test
    void loadsTinyV2ClassMappingsFromDefaultResource() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.loadDefault();

        MappingEntry classEntry = repository.requireClassByObfuscatedName("com/fs/graphics/TextureLoader");
        assertEquals("com/fs/graphics/TextureLoader", classEntry.namedName());
        assertEquals("com/fs/graphics/TextureLoader", classEntry.obfuscatedName());
    }

    @Test
    void foreignClassMappingsMustNotPointIntoSsoptimizerPackages() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.loadDefault();

        MappingEntry classEntry = repository.requireClassByObfuscatedName("com/fs/graphics/TextureLoader");
        org.junit.jupiter.api.Assertions.assertFalse(
                classEntry.namedName().startsWith("github/kasuminova/ssoptimizer/"),
                "外部类映射不应被重写进 SSOptimizer 自己的包命名空间");
    }

            @Test
            void rejectsForeignClassMappingsIntoSsoptimizerNamespaceDuringLoad() {
            String tiny = String.join("\n",
                "tiny 2 0 obf named",
                "c com/fs/example/ExternalClass github/kasuminova/ssoptimizer/bootstrap/FakeExternal") + "\n";

            MappingLookupException exception = assertThrows(MappingLookupException.class,
                () -> TinyV2MappingRepository.loadFromResource(
                    new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)),
                    "memory:test-invalid-namespace.tiny"));

            assertTrue(exception.getMessage().contains("外部类映射不得指向 SSOptimizer 命名空间"));
            }

    @Test
    void missingClassMappingThrowsReadableError() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.loadDefault();

        MappingLookupException exception = assertThrows(MappingLookupException.class,
            () -> repository.requireClassByNamedName("com/fs/graphics/MissingClass"));
        assertEquals("未找到类映射: com/fs/graphics/MissingClass", exception.getMessage());
    }

    @Test
    void parsesCommentLinesOntoOwningEntries() {
        String tiny = String.join("\n",
            "tiny 2 0 obf named",
            "c com/fs/example/A com/fs/example/Alpha",
            "\tc 来源: javap取证; 置信度: 高; 证据: .dev/mapping-evidence/a.md",
            "\tf a alphaField I",
            "\t\tc 成员级注释",
            "\tm b doWork (I)V",
            "c com/fs/example/B com/fs/example/Beta",
            "\tc 第一行注释",
            "\tc 第二行注释") + "\n";

        TinyV2MappingRepository repository = TinyV2MappingRepository.loadFromResource(
            new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)),
            "memory:test-comments.tiny");

        MappingEntry classEntry = repository.requireClassByObfuscatedName("com/fs/example/A");
        assertEquals("来源: javap取证; 置信度: 高; 证据: .dev/mapping-evidence/a.md", classEntry.comment());

        MappingEntry fieldEntry = repository.requireFieldByObfuscatedName("com/fs/example/A", "a");
        assertEquals("成员级注释", fieldEntry.comment());

        MappingEntry methodEntry = repository.requireMethodByObfuscatedName("com/fs/example/A", "b", "(I)V");
        assertEquals(null, methodEntry.comment());

        MappingEntry multiLine = repository.requireClassByObfuscatedName("com/fs/example/B");
        assertEquals("第一行注释\n第二行注释", multiLine.comment());
    }

    @Test
    void loadsGzipCompressedResourceIdenticallyToPlainText() throws Exception {
        String tiny = String.join("\n",
            "tiny 2 0 obf named",
            "c com/fs/example/GzipA com/fs/example/GzipAlpha",
            "\tf obfField namedField I",
            "\tm obfMethod namedMethod (I)V") + "\n";
        byte[] plainBytes = tiny.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream gzipBuffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipStream = new GZIPOutputStream(gzipBuffer)) {
            gzipStream.write(plainBytes);
        }

        TinyV2MappingRepository plain = TinyV2MappingRepository.loadFromResource(
            new ByteArrayInputStream(plainBytes), "memory:test-gzip-plain.tiny");
        TinyV2MappingRepository gzipped = TinyV2MappingRepository.loadFromResource(
            new ByteArrayInputStream(gzipBuffer.toByteArray()), "memory:test-gzip-compressed.tiny.gz");

        assertEquals(describeEntries(plain), describeEntries(gzipped));
    }

    private static List<String> describeEntries(TinyV2MappingRepository repository) {
        return repository.entries().stream()
            .map(entry -> entry.kind() + "|" + entry.ownerObfuscatedName() + "|" + entry.ownerNamedName()
                + "|" + entry.obfuscatedName() + "|" + entry.namedName() + "|" + entry.descriptor())
            .toList();
    }

    @Test
    void parsesThreeColumnTableWithIntermediaryNamespace() {
        String tiny = String.join("\n",
            "tiny 2 0 obf intermediary named",
            "c com/example/oo com/example/C_aaaa1111 com/example/Named",
            "\tc 人工注释",
            "\tf a f_1111aaaa counter I",
            "c com/example/o0 com/example/C_bbbb2222",
            "\tf c f_5555eeee I",
            "\tm b m_2222bbbb (Lcom/example/oo;)V") + "\n";

        TinyV2MappingRepository repository = TinyV2MappingRepository.loadFromResource(
            new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)),
            "memory:test-three-column.tiny");

        // 类条目：obf / intermediary 双索引可查，named 为空的类不进 named 索引。
        assertEquals("com/example/Named",
                repository.requireClassByObfuscatedName("com/example/oo").namedOrIntermediary());
        assertEquals("com/example/oo",
                repository.requireClassByIntermediaryName("com/example/C_aaaa1111").obfuscatedName());
        MappingEntry unnamedClass = repository.requireClassByObfuscatedName("com/example/o0");
        assertEquals(null, unnamedClass.namedName());
        assertEquals("com/example/C_bbbb2222", unnamedClass.namedOrIntermediary());
        assertTrue(repository.findClassByNamedName("com/example/C_bbbb2222").isEmpty());
        assertTrue(repository.findClassByIntermediaryName("com/example/C_bbbb2222").isPresent());

        // 命名成员：named 索引可查（owner 为 named 类名）。
        assertTrue(repository.findFieldByNamedName("com/example/Named", "counter").isPresent());
        assertTrue(repository.findFieldByIntermediaryName("com/example/C_aaaa1111", "f_1111aaaa").isPresent());

        // 未命名成员：named 列为空，ownerNamed 落 owner 的 intermediary 名；
        // intermediary 索引以 obf 侧描述符为 key（desc canonical = obf）。
        MappingEntry method = repository.requireMethodByObfuscatedName("com/example/o0", "b", "(Lcom/example/oo;)V");
        assertEquals(null, method.namedName());
        assertEquals("m_2222bbbb", method.namedOrIntermediary());
        assertEquals("com/example/C_bbbb2222", method.ownerNamedName());
        assertTrue(repository.findMethodByIntermediaryName(
                "com/example/C_bbbb2222", "m_2222bbbb", "(Lcom/example/oo;)V").isPresent());
        assertTrue(repository.findFieldByIntermediaryName("com/example/C_bbbb2222", "f_5555eeee").isPresent());
    }

    @Test
    void rejectsUnsupportedNamespaceLayout() {
        String tiny = String.join("\n",
            "tiny 2 0 obf hashed named",
            "c com/example/A com/example/B com/example/C") + "\n";

        MappingLookupException exception = assertThrows(MappingLookupException.class,
            () -> TinyV2MappingRepository.loadFromResource(
                new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)),
                "memory:test-bad-layout.tiny"));
        assertTrue(exception.getMessage().contains("命名空间布局不支持"));
    }

    @Test
    void rejectsUnknownLinesEvenWithCommentSupport() {
        String tiny = String.join("\n",
            "tiny 2 0 obf named",
            "c com/fs/example/A com/fs/example/Alpha",
            "\tx a alphaField I") + "\n";

        assertThrows(MappingLookupException.class,
            () -> TinyV2MappingRepository.loadFromResource(
                new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)),
                "memory:test-unknown-line.tiny"));
    }
}
