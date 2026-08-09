package io.github.nanoforged.sourcesector.mapping.core;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 测试用合成 jar 构建器：ASM {@link ClassWriter} 直接生成类字节，
 * 避免依赖手写二进制与真实游戏资产，各组件测试可独立构造场景。
 */
public final class TestJars {

    private TestJars() {
    }

    /** 成员规格。 */
    public record MemberSpec(int access, String name, String desc) {
    }

    /**
     * 类规格。
     *
     * @param name       内部名
     * @param superName  父类内部名（null 按 Object 处理）
     * @param interfaces 接口内部名（按声明顺序）
     * @param fields     字段
     * @param methods    方法
     */
    public record ClassSpec(String name, String superName, List<String> interfaces,
                            List<MemberSpec> fields, List<MemberSpec> methods) {

        /** 便捷构造：无成员类。 */
        public static ClassSpec clazz(String name, String superName, String... interfaces) {
            return new ClassSpec(name, superName, List.of(interfaces), List.of(), List.of());
        }

        /** 便捷构造：带字段与方法的类。 */
        public static ClassSpec withMembers(String name, String superName,
                                            List<MemberSpec> fields, List<MemberSpec> methods) {
            return new ClassSpec(name, superName, List.of(), fields, methods);
        }

        /** 便捷构造：公开字段。 */
        public static MemberSpec field(String name, String desc) {
            return new MemberSpec(Opcodes.ACC_PUBLIC, name, desc);
        }

        /** 便捷构造：公开方法。 */
        public static MemberSpec method(String name, String desc) {
            return new MemberSpec(Opcodes.ACC_PUBLIC, name, desc);
        }
    }

    /** 生成类字节码。 */
    public static byte[] bytecode(ClassSpec spec) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, spec.name(), null,
                spec.superName() == null ? "java/lang/Object" : spec.superName(),
                spec.interfaces().toArray(String[]::new));
        for (MemberSpec field : spec.fields()) {
            cw.visitField(field.access(), field.name(), field.desc(), null, null).visitEnd();
        }
        for (MemberSpec method : spec.methods()) {
            cw.visitMethod(method.access(), method.name(), method.desc(), null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 按给定顺序写入条目构造 jar。 */
    public static Path jar(Path dir, String jarName, List<ClassSpec> specs) throws IOException {
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (ClassSpec spec : specs) {
                out.putNextEntry(new JarEntry(spec.name() + ".class"));
                out.write(bytecode(spec));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** 便捷构造。 */
    public static Path jar(Path dir, String jarName, ClassSpec... specs) throws IOException {
        return jar(dir, jarName, List.of(specs));
    }

    /** 条目逆序构造 jar（zip 条目顺序无关性的确定性测试用）。 */
    public static Path jarReversed(Path dir, String jarName, List<ClassSpec> specs) throws IOException {
        List<ClassSpec> reversed = new ArrayList<>(specs);
        java.util.Collections.reverse(reversed);
        return jar(dir, jarName, reversed);
    }

    /** 构造含非 class 条目的 jar（module-info、多版本目录等排除规则测试用）。 */
    public static Path jarWithExtras(Path dir, String jarName, ClassSpec... specs) throws IOException {
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (ClassSpec spec : specs) {
                out.putNextEntry(new JarEntry(spec.name() + ".class"));
                out.write(bytecode(spec));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("module-info.class"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/versions/9/module-info.class"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/versions/9/java/lang/Object.class"));
            out.write(bytecode(ClassSpec.clazz("java/lang/Object", null)));
            out.closeEntry();
            out.putNextEntry(new JarEntry("java/lang/Object.class"));
            out.write(bytecode(ClassSpec.clazz("java/lang/Object", null)));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
