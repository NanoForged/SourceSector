package io.github.nanoforged.sourcesector.mapping;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BytecodeRemapper} 在类名重命名场景下的方法 remap 回归测试。
 */
class BytecodeRemapperTest {
    private static final MappingLookup LOOKUP = new MappingLookup(TinyV2MappingRepository.loadDefault());

    @Test
    void remapsMethodNameAndDescriptorForRenamedOwnerClass() {
        BytecodeRemapper remapper = new BytecodeRemapper(
                TinyV2MappingRepository.loadDefault(),
                MappingDirection.OBFUSCATED_TO_NAMED);
    MappingEntry classEntry = LOOKUP.requireClassByNamedName("com/fs/graphics/font/BitmapFontManager");
    MappingEntry methodEntry = LOOKUP.requireMethodByNamedName(
        "com/fs/graphics/font/BitmapFontManager",
        "getFont",
        "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;");
    MappingEntry bitmapFontClassEntry = LOOKUP.requireClassByNamedName("com/fs/graphics/font/BitmapFont");

    BytecodeRemapper.RemappedClass remapped = remapper.remapClass(createObfuscatedBitmapFontManager(
        classEntry.obfuscatedName(),
        methodEntry.obfuscatedName(),
        "(Ljava/lang/String;)L" + bitmapFontClassEntry.obfuscatedName() + ";"));

        assertTrue(remapped.modified());
    assertEquals(classEntry.obfuscatedName(), remapped.inputInternalName());
        assertEquals("com/fs/graphics/font/BitmapFontManager", remapped.outputInternalName());

        ClassReader reader = new ClassReader(remapped.bytecode());
        assertEquals("com/fs/graphics/font/BitmapFontManager", reader.getClassName());

        boolean[] foundLookupMethod = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("getFont".equals(name)
                        && "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;".equals(descriptor)) {
                    foundLookupMethod[0] = true;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        assertTrue(foundLookupMethod[0], "renamed owner class should also remap its method name and descriptor");
    }

    @Test
    void remapsSoundManagerPathMethodForRenamedOwnerClass() {
        BytecodeRemapper remapper = new BytecodeRemapper(
                TinyV2MappingRepository.loadDefault(),
                MappingDirection.OBFUSCATED_TO_NAMED);
        MappingEntry classEntry = LOOKUP.requireClassByNamedName("sound/SoundManager");
        MappingEntry methodEntry = LOOKUP.requireMethodByNamedName(
            "sound/SoundManager",
            "loadOAccentFamily",
            "(Ljava/lang/String;)Lsound/Audio;");

        BytecodeRemapper.RemappedClass remapped = remapper.remapClass(createObfuscatedSoundManager(
            classEntry.obfuscatedName(),
            methodEntry.obfuscatedName(),
            methodEntry.descriptor()));

        assertTrue(remapped.modified());
        assertEquals(classEntry.obfuscatedName(), remapped.inputInternalName());
        assertEquals("sound/SoundManager", remapped.outputInternalName());

        final boolean[] foundNamedMethod = {false};
        new ClassReader(remapped.bytecode()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                if ("loadOAccentFamily".equals(name)
                        && "(Ljava/lang/String;)Lsound/Audio;".equals(descriptor)) {
                    foundNamedMethod[0] = true;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        assertTrue(foundNamedMethod[0], "sound manager path loader should remap to the named method");
    }

    @Test
    void threeColumnTableRemapsIdenticallyToLegacyDoubleColumnTable() {
        // 旧双列表：占位名写在 named 列。
        TinyV2MappingRepository legacy = TinyV2MappingRepository.of(java.util.List.of(
                MappingEntry.classEntry("com/example/o0", "com/example/C_bbbb2222"),
                MappingEntry.fieldEntry("com/example/o0", "com/example/C_bbbb2222", "a", "f_1111aaaa", "I"),
                MappingEntry.methodEntry("com/example/o0", "com/example/C_bbbb2222", "b", "m_2222bbbb", "()I")));
        // 新三列表：占位名写在 intermediary 列，named 为空（remap 目标规则 named ?: intermediary）。
        TinyV2MappingRepository threeColumn = TinyV2MappingRepository.of(java.util.List.of(
                MappingEntry.classEntry("com/example/o0", "com/example/C_bbbb2222", null),
                MappingEntry.fieldEntry("com/example/o0", "com/example/C_bbbb2222", "a", "f_1111aaaa", null, "I"),
                MappingEntry.methodEntry("com/example/o0", "com/example/C_bbbb2222", "b", "m_2222bbbb", null, "()I")));

        byte[] input = createClassWithFieldAndGetter("com/example/o0", "a", "b");
        byte[] legacyOutput = new BytecodeRemapper(legacy, MappingDirection.OBFUSCATED_TO_NAMED)
                .remapClass(input).bytecode();
        BytecodeRemapper.RemappedClass threeColumnOutput = new BytecodeRemapper(threeColumn, MappingDirection.OBFUSCATED_TO_NAMED)
                .remapClass(input);

        org.junit.jupiter.api.Assertions.assertArrayEquals(legacyOutput, threeColumnOutput.bytecode(),
                "三列表（named 空 + intermediary 锚点）remap 结果应与旧双列表字节级一致");
        assertEquals("com/example/C_bbbb2222", threeColumnOutput.outputInternalName());
    }

    @Test
    void namedToObfuscatedResolvesIntermediaryNames() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.of(java.util.List.of(
                MappingEntry.classEntry("com/example/oo", "com/example/C_aaaa1111", "com/example/Named"),
                MappingEntry.classEntry("com/example/o0", "com/example/C_bbbb2222", null),
                MappingEntry.methodEntry("com/example/oo", "com/example/Named", "c", "m_3333cccc", "namedMethod", "()V"),
                MappingEntry.methodEntry("com/example/o0", "com/example/C_bbbb2222", "b", "m_2222bbbb", null, "(Lcom/example/oo;)V")));

        // named jar 形态：未命名类/成员呈现为 intermediary 名，方法描述符引用 named 类。
        byte[] namedInput = createClassCallingMethod(
                "com/example/C_bbbb2222", "m_2222bbbb", "(Lcom/example/Named;)V",
                "com/example/Named", "namedMethod", "()V");
        BytecodeRemapper.RemappedClass remapped = new BytecodeRemapper(repository, MappingDirection.NAMED_TO_OBFUSCATED)
                .remapClass(namedInput);

        assertEquals("com/example/o0", remapped.outputInternalName(),
                "intermediary 类名应解析回混淆类名");

        boolean[] foundObfuscatedMethod = {false};
        boolean[] foundObfuscatedCall = {false};
        new ClassReader(remapped.bytecode()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("b".equals(name) && "(Lcom/example/oo;)V".equals(descriptor)) {
                    foundObfuscatedMethod[0] = true;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if ("com/example/oo".equals(owner) && "c".equals(methodName)) {
                            foundObfuscatedCall[0] = true;
                        }
                    }
                };
            }
        }, 0);
        assertTrue(foundObfuscatedMethod[0], "intermediary 方法名与 named 描述符应解析回混淆形式");
        assertTrue(foundObfuscatedCall[0], "named 方法调用应解析回混淆形式");
    }

    private static byte[] createClassWithFieldAndGetter(String internalName, String fieldName, String methodName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, fieldName, "I", null, null).visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, "I");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createClassCallingMethod(String internalName,
                                                   String methodName,
                                                   String methodDescriptor,
                                                   String calleeOwner,
                                                   String calleeName,
                                                   String calleeDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, methodDescriptor, null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, calleeOwner, calleeName, calleeDescriptor, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createObfuscatedBitmapFontManager(final String ownerObfuscatedName,
                                                            final String methodObfuscatedName,
                                                            final String methodDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                ownerObfuscatedName,
                null,
                "java/lang/Object",
                null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            methodObfuscatedName,
            methodDescriptor,
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createObfuscatedSoundManager(final String ownerObfuscatedName,
                                                       final String methodObfuscatedName,
                                                       final String methodDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                ownerObfuscatedName,
                null,
                "java/lang/Object",
                null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
            methodObfuscatedName,
            methodDescriptor,
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}