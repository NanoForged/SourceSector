package io.github.nanoforged.sourcesector.mapping.core;

import io.github.nanoforged.sourcesector.mapping.core.ClassStructure;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 从 jar 加载类结构集合。
 * <p>
 * 确定性纪律：jar 列表先按路径排序再扫描，结果合并进按内部名排序的
 * {@link TreeMap}；zip 条目顺序与调用方传参顺序均不影响结果。
 * <p>
 * 排除规则：跳过目录、非 {@code .class} 条目、{@code module-info.class}、
 * {@code META-INF/versions/**}（多版本 jar 只取根版本）与 {@code java/lang/Object}
 * （合成根节点由图构建兜底，任何输入都不得映射它）。
 * <p>
 * 重复策略：同一类出现在多个输入 jar 中直接报错（输入歧义，显式失败）；
 * 库 jar 间重复取首个（按 jar 路径排序后先到先得）；同一类同时出现在输入与库中时，
 * 输入侧优先（库只是层次分析的补充，结构取输入侧）。
 */
public final class ClassProvider {

    private ClassProvider() {
    }

    /**
     * 加载类集合。
     *
     * @param inputJars   输入混淆 jar
     * @param libraryJars 库 jar（可空列表）
     * @return 类集合
     * @throws IOException           读取 jar 失败
     * @throws SourceSectorException 输入 jar 间存在重复类
     */
    public static ClassSet load(List<java.nio.file.Path> inputJars,
                                List<java.nio.file.Path> libraryJars) throws IOException {
        SortedMap<String, ClassStructure> inputs = scan(inputJars, true);
        SortedMap<String, ClassStructure> libraries = scan(libraryJars, false);
        return new ClassSet(inputs, libraries);
    }

    private static SortedMap<String, ClassStructure> scan(List<java.nio.file.Path> jars,
                                                          boolean errorOnDuplicate) throws IOException {
        SortedMap<String, ClassStructure> result = new TreeMap<>();
        Map<String, String> firstSeenIn = new HashMap<>();
        List<java.nio.file.Path> sorted = new ArrayList<>(jars);
        sorted.sort(Comparator.comparing(java.nio.file.Path::toString));
        for (java.nio.file.Path jarFile : sorted) {
            String jarName = jarFile.toString();
            try (JarFile jar = new JarFile(jarFile.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!isClassEntry(entry)) {
                        continue;
                    }
                    ClassStructure structure;
                    try (InputStream stream = jar.getInputStream(entry)) {
                        structure = read(stream);
                    }
                    if (errorOnDuplicate && result.containsKey(structure.name())) {
                        throw new SourceSectorException(
                                "输入 jar 包含重复类 " + structure.name() + "：" + firstSeenIn.get(structure.name()) + " 与 " + jarName);
                    }
                    result.putIfAbsent(structure.name(), structure);
                    firstSeenIn.putIfAbsent(structure.name(), jarName);
                }
            }
        }
        return result;
    }

    private static boolean isClassEntry(JarEntry entry) {
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
            return false;
        }
        String name = entry.getName();
        if ("module-info.class".equals(name) || "java/lang/Object.class".equals(name)) {
            return false;
        }
        return !name.startsWith("META-INF/versions/");
    }

    private static ClassStructure read(InputStream stream) throws IOException {
        ClassReader reader = new ClassReader(stream);
        StructureCollector collector = new StructureCollector();
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return collector.toClassStructure();
    }

    /** 与 {@code gen.ClassStructure} 内部收集器同构：只采集继承关系与成员签名，不读方法体。 */
    private static final class StructureCollector extends ClassVisitor {
        private String name;
        private String superName;
        private List<String> interfaces = List.of();
        private final List<ClassStructure.Member> fields = new ArrayList<>();
        private final List<ClassStructure.Member> methods = new ArrayList<>();

        private StructureCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            fields.add(new ClassStructure.Member(name, descriptor, access, value));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            methods.add(new ClassStructure.Member(name, descriptor, access));
            return null;
        }

        private ClassStructure toClassStructure() {
            return new ClassStructure(name, superName, interfaces, List.copyOf(fields), List.copyOf(methods));
        }
    }
}
