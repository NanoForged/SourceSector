package io.github.nanoforged.sourcesector.mapping.gen;

import io.github.nanoforged.sourcesector.mapping.gen.NamedJarLinkValidator.Violation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * named 游戏 jar 成员链接校验器测试。
 * <p>
 * 用 ASM 构造微型 class 样本（与 {@code JarRemapperTest} 同风格），在临时 jar 上跑完整
 * 索引构建 + 字节码扫描 + 继承链解析，真实验证：继承链名字分叉检出、索引外 owner 跳过、
 * 外部父类声明视为可解析、字段引用沿链校验、Object 兜底、构造器不沿链、lambda 句柄校验。
 */
class NamedJarLinkValidatorTest {

    @Test
    void inheritanceChainNameDivergenceIsDetected() throws IOException {
        ClassWriter base = newClass("com/example/Base");
        defaultCtor(base, "java/lang/Object");
        method(base, Opcodes.ACC_PUBLIC, "tick", "()V", mv -> mv.visitInsn(Opcodes.RETURN));

        ClassWriter child = newClass("com/example/Child", "com/example/Base");
        defaultCtor(child, "com/example/Base");
        method(child, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            // 父类声明了 tick：应可解析；父类未声明 oldTick：名字分叉，应断裂。
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Base", "tick", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Base", "oldTick", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        });

        List<Violation> violations = validate(Map.of(
                "com/example/Base", finish(base),
                "com/example/Child", finish(child)));
        assertEquals(1, violations.size(), "父类未声明的引用名应检出断裂: " + violations);
        Violation violation = violations.get(0);
        assertEquals("com/example/Child", violation.referencingClass());
        assertEquals("run()V", violation.referencingMethod());
        assertEquals("com/example/Base", violation.targetOwner());
        assertEquals("oldTick", violation.targetName());
        assertEquals("()V", violation.targetDesc());
    }

    @Test
    void externalOwnerReferencesAreSkipped() throws IOException {
        ClassWriter clazz = newClass("com/example/ExternalUser");
        defaultCtor(clazz, "java/lang/Object");
        method(clazz, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glClear", "(I)V", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/json/JSONObject", "toString", "()Ljava/lang/String;", false);
            mv.visitInsn(Opcodes.RETURN);
        });
        assertTrue(validate(Map.of("com/example/ExternalUser", finish(clazz))).isEmpty(),
                "索引外 owner（JDK / 第三方）引用应全部跳过");
    }

    @Test
    void externalSuperclassDeclarationsAreResolvable() throws IOException {
        ClassWriter jsonChild = newClass("com/example/JsonChild", "org/json/JSONObject");
        defaultCtor(jsonChild, "org/json/JSONObject");
        method(jsonChild, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            // 引用自身方法（声明可能在外部父类 JSONObject）：链走到索引外父类，无法证伪，应可解析。
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/JsonChild", "toString", "()Ljava/lang/String;", false);
            // 直接引用外部类方法：owner 索引外，应跳过。
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/json/JSONObject", "opt", "(Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.RETURN);
        });
        assertTrue(validate(Map.of("com/example/JsonChild", finish(jsonChild))).isEmpty(),
                "外部父类可能声明，索引内无法证伪，不应报断裂");
    }

    @Test
    void fieldReferencesAreCheckedAlongInheritanceChain() throws IOException {
        ClassWriter base = newClass("com/example/Base");
        defaultCtor(base, "java/lang/Object");
        field(base, Opcodes.ACC_PUBLIC, "speed", "F");

        ClassWriter child = newClass("com/example/Child", "com/example/Base");
        defaultCtor(child, "com/example/Base");
        method(child, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            // speed 在父类声明：沿链应可解析；ghost 无处声明：应断裂。
            mv.visitFieldInsn(Opcodes.GETFIELD, "com/example/Child", "speed", "F");
            mv.visitFieldInsn(Opcodes.GETFIELD, "com/example/Child", "ghost", "F");
            mv.visitInsn(Opcodes.RETURN);
        });

        List<Violation> violations = validate(Map.of(
                "com/example/Base", finish(base),
                "com/example/Child", finish(child)));
        assertEquals(1, violations.size(), "父类声明的字段应可解析，未声明的字段应检出: " + violations);
        assertEquals("ghost", violations.get(0).targetName());
        assertTrue(violations.get(0).kind().contains("字段"), "断裂种类应为字段引用: " + violations.get(0));
    }

    @Test
    void objectFallbackResolvesDeclaredMembersAndFlagsTheRest() throws IOException {
        ClassWriter base = newClass("com/example/Base");
        defaultCtor(base, "java/lang/Object");

        ClassWriter child = newClass("com/example/Child", "com/example/Base");
        defaultCtor(child, "com/example/Base");
        method(child, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            // toString 是 Object 声明的方法：走到 Object 应可解析；ghost 无处声明：应断裂。
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Base", "toString", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Base", "ghost", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        });

        List<Violation> violations = validate(Map.of(
                "com/example/Base", finish(base),
                "com/example/Child", finish(child)));
        assertEquals(1, violations.size(), "Object 声明的 toString 应可解析，ghost 应断裂: " + violations);
        assertEquals("ghost", violations.get(0).targetName());
    }

    @Test
    void invokedynamicAndLdcHandlesAreChecked() throws IOException {
        ClassWriter host = newClass("com/example/LambdaHost");
        defaultCtor(host, "java/lang/Object");
        method(host, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "impl", "()V", mv -> mv.visitInsn(Opcodes.RETURN));
        method(host, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "staticImpl", "()V", mv -> mv.visitInsn(Opcodes.RETURN));

        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        method(host, Opcodes.ACC_PUBLIC, "create", "()V", mv -> {
            // 合法 lambda：impl 句柄指向已声明的私有方法，bootstrap（外部类）跳过。
            mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", metafactory,
                    Type.getType("()V"),
                    new Handle(Opcodes.H_INVOKESTATIC, "com/example/LambdaHost", "impl", "()V", false),
                    Type.getType("()V"));
            // 断裂 lambda：impl 句柄指向未声明的方法。
            mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", metafactory,
                    Type.getType("()V"),
                    new Handle(Opcodes.H_INVOKESTATIC, "com/example/LambdaHost", "missing", "()V", false),
                    Type.getType("()V"));
            // ldc MethodHandle 常量：已声明的应可解析，未声明的应断裂。
            mv.visitLdcInsn(new Handle(Opcodes.H_INVOKESTATIC, "com/example/LambdaHost", "staticImpl", "()V", false));
            mv.visitLdcInsn(new Handle(Opcodes.H_INVOKESTATIC, "com/example/LambdaHost", "absent", "()V", false));
            mv.visitInsn(Opcodes.RETURN);
        });

        List<Violation> violations = validate(Map.of("com/example/LambdaHost", finish(host)));
        assertEquals(2, violations.size(), "lambda 实现句柄与 ldc 句柄中的未声明引用应检出: " + violations);
        assertTrue(violations.stream().anyMatch(v -> "missing".equals(v.targetName())));
        assertTrue(violations.stream().anyMatch(v -> "absent".equals(v.targetName())));
    }

    @Test
    void constructorReferencesOnlyResolveInOwner() throws IOException {
        ClassWriter base = newClass("com/example/Base");
        defaultCtor(base, "java/lang/Object");

        ClassWriter child = newClass("com/example/Child", "com/example/Base");
        method(child, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            // Base 自身声明了 <init>：可解析；Child 未声明 <init>：构造器不沿继承链，应断裂。
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/example/Base", "<init>", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/example/Child", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        });

        List<Violation> violations = validate(Map.of(
                "com/example/Base", finish(base),
                "com/example/Child", finish(child)));
        assertEquals(1, violations.size(), "构造器不沿继承链解析，Child 未声明的应断裂: " + violations);
        assertEquals("com/example/Child", violations.get(0).targetOwner());
        assertEquals("<init>", violations.get(0).targetName());
    }

    @Test
    void arrayAndPrimitiveOwnersAreSkipped() throws IOException {
        ClassWriter clazz = newClass("com/example/ArrayUser");
        defaultCtor(clazz, "java/lang/Object");
        method(clazz, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            // 数组 clone 是 JVM 内置行为，owner 为数组类型：应跳过。
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[Ljava/lang/String;", "clone", "()Ljava/lang/Object;", false);
            // 防御构造：基本类型 owner 的字段引用（合法字节码中不存在，校验器应跳过而不报错）。
            mv.visitFieldInsn(Opcodes.GETSTATIC, "I", "x", "I");
            mv.visitInsn(Opcodes.RETURN);
        });
        assertTrue(validate(Map.of("com/example/ArrayUser", finish(clazz))).isEmpty(),
                "数组 / 基本类型 owner 不参与成员解析");
    }

    @Test
    void consistentInheritanceProducesNoViolations() throws IOException {
        ClassWriter base = newClass("com/example/Base");
        defaultCtor(base, "java/lang/Object");
        method(base, Opcodes.ACC_PUBLIC, "tick", "()V", mv -> mv.visitInsn(Opcodes.RETURN));

        ClassWriter child = newClass("com/example/Child", "com/example/Base");
        defaultCtor(child, "com/example/Base");
        method(child, Opcodes.ACC_PUBLIC, "run", "()V", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Base", "tick", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/example/Base", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        });

        assertTrue(validate(Map.of(
                "com/example/Base", finish(base),
                "com/example/Child", finish(child))).isEmpty(),
                "自洽的继承链不应产生任何断裂");
    }

    private static ClassWriter newClass(String name) {
        return newClass(name, "java/lang/Object");
    }

    private static ClassWriter newClass(String name, String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        return writer;
    }

    private static void field(ClassWriter writer, int access, String name, String desc) {
        writer.visitField(access, name, desc, null, null).visitEnd();
    }

    private static void method(ClassWriter writer, int access, String name, String desc, Consumer<MethodVisitor> body) {
        MethodVisitor mv = writer.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void defaultCtor(ClassWriter writer, String superName) {
        method(writer, Opcodes.ACC_PUBLIC, "<init>", "()V", mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        });
    }

    private static byte[] finish(ClassWriter writer) {
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<Violation> validate(Map<String, byte[]> classes) throws IOException {
        Path jar = Files.createTempFile("named-link-validator-test", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        try {
            NamedJarClassIndex index = NamedJarClassIndex.build(List.of(jar));
            return new NamedJarLinkValidator(index).validate(List.of(jar));
        } finally {
            Files.deleteIfExists(jar);
        }
    }
}
