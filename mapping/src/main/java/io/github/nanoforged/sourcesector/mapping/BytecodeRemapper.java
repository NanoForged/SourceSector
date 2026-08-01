package io.github.nanoforged.sourcesector.mapping;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.Objects;

/**
 * 基于 Tiny v2 映射仓库的字节码重映射器。
 * <p>
 * 该组件负责在 class 级别统一改写类名、字段名、方法名和描述符，供运行时
 * {@code RuntimeRemapTransformer} 与编译期 jar remap 共用同一套规则。
 */
public final class BytecodeRemapper {
    private final MappingRepository repository;
    private final MappingDirection direction;

    /**
     * 创建字节码重映射器。
     *
     * @param repository 映射仓库
     * @param direction  重映射方向
     */
    public BytecodeRemapper(MappingRepository repository, MappingDirection direction) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    /**
     * 重映射单个类文件字节码。
     *
     * @param classfileBuffer 原始类文件字节码
     * @return 重映射结果
     */
    public RemappedClass remapClass(byte[] classfileBuffer) {
        Objects.requireNonNull(classfileBuffer, "classfileBuffer");

        ClassReader reader = new ClassReader(classfileBuffer);
        RepositoryBackedRemapper remapper = new RepositoryBackedRemapper();
        // 不复用原常量池（new ClassWriter(reader, 0) 会逐字拷贝原 CP，把混淆器留下的
        // 孤儿 Methodref/Fieldref 项（如未被任何指令引用的 "do.new"）带进产物；
        // 运行期 SanitizingRemapper 只能改写可达项，JDK 25 对变换器修改过的类强制
        // 格式检查时会被孤儿项击杀）。全新 ClassWriter 只保留可达常量。
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);

        String inputInternalName = reader.getClassName();
        String outputInternalName = remapper.map(inputInternalName);
        byte[] outputBytes = remapper.modified() ? writer.toByteArray() : classfileBuffer;
        return new RemappedClass(inputInternalName, outputInternalName, outputBytes, remapper.modified());
    }

    /**
     * 单个类 remap 的结果。
     *
     * @param inputInternalName  输入类名
     * @param outputInternalName 输出类名
     * @param bytecode           输出字节码
     * @param modified           是否发生改写
     */
    public record RemappedClass(String inputInternalName,
                                String outputInternalName,
                                byte[] bytecode,
                                boolean modified) {
    }

    private final class RepositoryBackedRemapper extends Remapper {
        private boolean modified;

        @Override
        public String mapDesc(String descriptor) {
            String mappedDescriptor = super.mapDesc(descriptor);
            if (!descriptor.equals(mappedDescriptor)) {
                modified = true;
            }
            return mappedDescriptor;
        }

        @Override
        public String mapMethodDesc(String descriptor) {
            String mappedDescriptor = super.mapMethodDesc(descriptor);
            if (!descriptor.equals(mappedDescriptor)) {
                modified = true;
            }
            return mappedDescriptor;
        }

        @Override
        public String map(String internalName) {
            MappingEntry classEntry = findClass(internalName);
            if (classEntry == null) {
                return internalName;
            }

            String mappedName = switch (direction) {
                // 目标规则 named ?: intermediary：未命名类落 intermediary 占位名，
                // 与旧双列全量表（占位名写在 named 列）的 remap 结果一致。
                case OBFUSCATED_TO_NAMED -> classEntry.namedOrIntermediary();
                case NAMED_TO_OBFUSCATED -> classEntry.obfuscatedName();
            };
            if (!internalName.equals(mappedName)) {
                modified = true;
            }
            return mappedName;
        }

        /**
         * 按方向解析类条目。named→obf 方向的目标侧名分布在 named 与 intermediary
         * 两个命名空间（未命名类/成员在目标侧呈现为 intermediary 占位名），
         * 故 named 索引未命中时查 intermediary 索引。
         */
        private MappingEntry findClass(String internalName) {
            return switch (direction) {
                case OBFUSCATED_TO_NAMED -> repository.findClassByObfuscatedName(internalName).orElse(null);
                case NAMED_TO_OBFUSCATED -> repository.findClassByNamedName(internalName)
                        .or(() -> repository.findClassByIntermediaryName(internalName))
                        .orElse(null);
            };
        }

        /**
         * named→obf 方向下解析目标侧 owner 类的 intermediary 名，用于成员
         * intermediary 索引查询；owner 无映射或无锚点名（identity 类）时返回 {@code null}。
         */
        private String ownerIntermediaryOf(String owner) {
            if (direction != MappingDirection.NAMED_TO_OBFUSCATED) {
                return null;
            }
            MappingEntry ownerEntry = findClass(owner);
            return ownerEntry == null ? null : ownerEntry.intermediaryName();
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            MappingEntry fieldEntry = switch (direction) {
                case OBFUSCATED_TO_NAMED -> repository.findFieldByObfuscatedName(owner, name).orElse(null);
                case NAMED_TO_OBFUSCATED -> repository.findFieldByNamedName(owner, name)
                        .or(() -> {
                            String ownerIntermediary = ownerIntermediaryOf(owner);
                            return ownerIntermediary == null
                                    ? java.util.Optional.<MappingEntry>empty()
                                    : repository.findFieldByIntermediaryName(ownerIntermediary, name);
                        })
                        .orElse(null);
            };
            if (fieldEntry == null) {
                return name;
            }

            String mappedName = switch (direction) {
                case OBFUSCATED_TO_NAMED -> fieldEntry.namedOrIntermediary();
                case NAMED_TO_OBFUSCATED -> fieldEntry.obfuscatedName();
            };
            if (!name.equals(mappedName)) {
                modified = true;
            }
            return mappedName;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            MappingEntry methodEntry = switch (direction) {
                case OBFUSCATED_TO_NAMED -> repository.findMethodByObfuscatedName(owner, name, descriptor).orElse(null);
                case NAMED_TO_OBFUSCATED -> repository.findMethodByNamedName(owner, name, descriptor)
                        .or(() -> {
                            // 成员 intermediary 索引以 obf 侧描述符为 key，
                            // 目标侧描述符需先逐类换算为 obf 形式。
                            String ownerIntermediary = ownerIntermediaryOf(owner);
                            return ownerIntermediary == null
                                    ? java.util.Optional.<MappingEntry>empty()
                                    : repository.findMethodByIntermediaryName(
                                            ownerIntermediary, name, toObfuscatedDescriptor(descriptor));
                        })
                        .orElse(null);
            };
            if (methodEntry == null) {
                return name;
            }

            String mappedName = switch (direction) {
                case OBFUSCATED_TO_NAMED -> methodEntry.namedOrIntermediary();
                case NAMED_TO_OBFUSCATED -> methodEntry.obfuscatedName();
            };
            if (!name.equals(mappedName)) {
                modified = true;
            }
            return mappedName;
        }

        /**
         * 目标侧描述符换算为 obf 侧：类名逐段按 named / intermediary 双索引解析，
         * 表外类（JDK / 第三方 / 未混淆 api）保持原样。
         */
        private String toObfuscatedDescriptor(String descriptor) {
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
                    throw new IllegalArgumentException("描述符格式不正确: " + descriptor);
                }
                String internalName = descriptor.substring(cursor + 1, end);
                MappingEntry classEntry = findClass(internalName);
                builder.append('L').append(classEntry == null ? internalName : classEntry.obfuscatedName()).append(';');
                cursor = end + 1;
            }
            return builder.toString();
        }

        boolean modified() {
            return modified;
        }
    }
}
