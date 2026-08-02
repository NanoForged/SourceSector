package io.github.nanoforged.sourcesector.mapping.gen;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * named 游戏 jar 成员链接校验器。
 * <p>
 * 校验 remap 产物的「跨类引用/声明名字分叉」缺陷：子类字节码对继承成员的引用名
 * 与父类中的声明名不一致时，运行期抛 {@code NoSuchMethodError}/{@code NoSuchFieldError}
 * （AssaultBattleStrategy 事故：{@code preCombat} 调用 {@code super(ZZ)V} 而父类并未声明）。
 * 现有的 {@link FullMappingMerger#duplicateRemapTargetLines(List, List)} 质量门只管
 * 「同类内 remap 目标名撞名」，管不到这一缺陷——本校验器对产物做整体链接校验。
 * <p>
 * 校验模型：以 4 个游戏 jar 建类成员索引，扫描全部方法体字节码引用
 * （{@code MethodInsn}/{@code FieldInsn}/常量池 {@code Handle}，后者覆盖 invokedynamic
 * 的 bootstrap 句柄与参数句柄、ldc 的 MethodHandle 常量），沿索引内继承链解析，
 * 解析规则见 {@link #resolves(String, String, String, boolean)}。
 * remap 只发生在 4 个游戏 jar 内部；对 JDK / 第三方库的引用不在校验范围。
 */
public final class NamedJarLinkValidator {
    /**
     * java.lang.Object 自身声明的方法（{@code name:desc} 键）——继承链兜底解析的最后一环。
     * Object 无实例字段，字段引用走到 Object 即断裂。
     */
    private static final Set<String> OBJECT_METHOD_KEYS = Set.of(
            "getClass:()Ljava/lang/Class;",
            "hashCode:()I",
            "equals:(Ljava/lang/Object;)Z",
            "clone:()Ljava/lang/Object;",
            "toString:()Ljava/lang/String;",
            "notify:()V",
            "notifyAll:()V",
            "wait:()V",
            "wait:(J)V",
            "wait:(JI)V",
            "finalize:()V");

    /**
     * 单条断裂引用。
     *
     * @param referencingClass  引用所在类（内部名）
     * @param referencingMethod 引用所在方法（{@code name+desc}）
     * @param kind              引用种类（方法 / 字段 / 方法句柄 / 字段句柄）
     * @param targetOwner       目标 owner 类（内部名）
     * @param targetName        目标成员名
     * @param targetDesc        目标成员描述符
     */
    public record Violation(String referencingClass,
                            String referencingMethod,
                            String kind,
                            String targetOwner,
                            String targetName,
                            String targetDesc) {
        /**
         * 单行描述：{@code 引用类.引用方法 -> 种类引用 owner.name(desc)}。
         *
         * @return 描述行
         */
        public String describe() {
            return referencingClass + "." + referencingMethod + " -> " + kind + " 引用 "
                    + targetOwner + "." + targetName + targetDesc;
        }
    }

    private final NamedJarClassIndex index;
    private final Set<Violation> violations = new LinkedHashSet<>();

    /**
     * 创建校验器。
     *
     * @param index 类成员索引（由 {@link NamedJarClassIndex#build} 构建）
     */
    public NamedJarLinkValidator(NamedJarClassIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    /**
     * 校验给定游戏 jar 的全部类文件字节码引用。
     * <p>
     * 扫描范围：方法体的 {@code MethodInsn}/{@code FieldInsn}、常量池 {@code Handle}
     * （invokedynamic 的 bootstrap 句柄与参数句柄、ldc 的 MethodHandle 常量）。
     * 断裂按（引用类, 引用方法, 目标 owner/name/desc）去重。
     *
     * @param gameJars 4 个游戏 named jar
     * @return 断裂清单（无断裂为空列表）
     * @throws IOException 若读取失败
     */
    public List<Violation> validate(List<Path> gameJars) throws IOException {
        Objects.requireNonNull(gameJars, "gameJars");
        violations.clear();
        for (Path jar : gameJars) {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream stream = jarFile.getInputStream(entry)) {
                        scanClass(stream);
                    }
                }
            }
        }
        return List.copyOf(violations);
    }

    /**
     * 生成报告文本（按目标 owner 类聚类排序，组内按引用类 / 引用方法排序）。
     *
     * @param violations 断裂清单
     * @param gameJars   参与校验的游戏 jar（用于报告头部说明索引范围）
     * @return 报告文本行
     */
    public static List<String> renderReport(List<Violation> violations, List<Path> gameJars) {
        List<String> lines = new ArrayList<>();
        lines.add("# named 游戏 jar 成员链接校验报告");
        lines.add("# 校验器: NamedJarLinkCli（Gradle 任务 :mapping:verifyNamedJarLinks）");
        lines.add("# 语义: 产物内自洽——成员引用必须沿索引内继承链解析到声明；索引外 owner 与外部父类/接口视为可解析；");
        lines.add("#       沿链走到 java.lang.Object 仍未找到声明（Object 自身成员除外）记为断裂（remap 名字分叉缺陷）。");
        lines.add("# 索引 jar: " + gameJars.stream()
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList());
        lines.add("断裂总数: " + violations.size());
        lines.add("涉及引用所在类数: " + violations.stream().map(Violation::referencingClass).distinct().count());
        lines.add("涉及目标 owner 类数: " + violations.stream().map(Violation::targetOwner).distinct().count());
        lines.add("");
        Map<String, List<Violation>> byOwner = new TreeMap<>();
        for (Violation violation : violations) {
            byOwner.computeIfAbsent(violation.targetOwner(), key -> new ArrayList<>()).add(violation);
        }
        for (Map.Entry<String, List<Violation>> group : byOwner.entrySet()) {
            List<Violation> sorted = new ArrayList<>(group.getValue());
            sorted.sort(Comparator.comparing(Violation::referencingClass).thenComparing(Violation::referencingMethod));
            lines.add("## " + group.getKey() + "（" + sorted.size() + " 条）");
            for (Violation violation : sorted) {
                lines.add("  " + violation.describe());
            }
            lines.add("");
        }
        return lines;
    }

    private void scanClass(InputStream classBytes) throws IOException {
        ClassReader reader = new ClassReader(classBytes);
        String className = reader.getClassName();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                String referencingMethod = name + descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        check(className, referencingMethod, "方法", owner, methodName, methodDescriptor, false);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        check(className, referencingMethod, "字段", owner, fieldName, fieldDescriptor, true);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Handle handle) {
                            checkHandle(className, referencingMethod, handle);
                        }
                        // Type（类常量）与 MethodType（方法描述符常量）不含 owner+name 成员引用，无可校验项，跳过。
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        // invokedynamic 的调用点 name/desc 由 BootstrapMethods 解析，自身不校验；
                        // bootstrap 方法句柄与参数句柄是真实成员引用，纳入校验（覆盖 lambda 的 impl 句柄）。
                        checkHandle(className, referencingMethod, bootstrapMethodHandle);
                        for (Object argument : bootstrapMethodArguments) {
                            if (argument instanceof Handle handle) {
                                checkHandle(className, referencingMethod, handle);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private void checkHandle(String referencingClass, String referencingMethod, Handle handle) {
        boolean field = isFieldHandle(handle.getTag());
        check(referencingClass, referencingMethod, field ? "字段句柄" : "方法句柄",
                handle.getOwner(), handle.getName(), handle.getDesc(), field);
    }

    /**
     * 单条引用的解析入口：按规则 a/b 过滤，命中索引内 owner 时沿继承链解析（规则 c/d/e）。
     */
    private void check(String referencingClass, String referencingMethod, String kind,
                       String owner, String name, String desc, boolean field) {
        if (owner == null || owner.isEmpty()) {
            return;
        }
        if (isArrayOrPrimitiveOwner(owner)) {
            // 规则 a：数组 / 基本类型 owner（如数组的 clone）不参与成员解析。
            return;
        }
        if (!index.contains(owner)) {
            // 规则 b：owner 不在索引内（JDK / 第三方 / 未参与索引的类）→ 外部引用，跳过。
            return;
        }
        if (!resolves(owner, name, desc, field)) {
            violations.add(new Violation(referencingClass, referencingMethod, kind, owner, name, desc));
        }
    }

    /**
     * 沿索引内继承链解析成员引用（规则 c/d/e）。
     * <p>
     * <ul>
     *     <li>c. owner 在索引内 → 沿 superclass/接口链（仅在索引内的部分）查找 name+desc 匹配的声明；</li>
     *     <li>d. 链走到索引外的非 Object 类 → 外部父类/接口可能声明，无法证伪，视为可解析；</li>
     *     <li>e. 走到 java/lang/Object 仍未找到 → Object 自身声明（{@link #OBJECT_METHOD_KEYS}）之外记为断裂。</li>
     * </ul>
     * {@code <init>}/{@code <clinit>} 是特殊方法不沿继承链解析（构造器不继承），只查 owner 自身声明。
     * 字段与方法的判定都按 remap 后名字直接比较——产物内自洽，无需再做命名空间换算。
     *
     * @param owner 目标 owner 类（已在索引内）
     * @param name  目标成员名
     * @param desc  目标成员描述符
     * @param field 是否为字段引用
     * @return 是否可解析
     */
    private boolean resolves(String owner, String name, String desc, boolean field) {
        NamedJarClassIndex.ClassEntry entry = index.entry(owner);
        if ("<init>".equals(name) || "<clinit>".equals(name)) {
            return entry.declaresMethod(name, desc);
        }
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            NamedJarClassIndex.ClassEntry currentEntry = index.entry(current);
            if (currentEntry == null) {
                if ("java/lang/Object".equals(current)) {
                    // 规则 e：Object 自身声明之外视为断裂；Object 无父类，队列排空后返回 false。
                    if (!field && OBJECT_METHOD_KEYS.contains(name + ":" + desc)) {
                        return true;
                    }
                    continue;
                }
                // 规则 d：索引外父类/接口可能声明，无法证伪，视为可解析。
                return true;
            }
            if (field ? currentEntry.declaresField(name, desc) : currentEntry.declaresMethod(name, desc)) {
                return true;
            }
            if (currentEntry.superName() != null) {
                pending.add(currentEntry.superName());
            }
            pending.addAll(currentEntry.interfaces());
        }
        return false;
    }

    /**
     * owner 是否为数组类型或基本类型（规则 a）。
     * <p>
     * 数组 owner 以 {@code [} 开头；基本类型 owner 是单个基本类型字母。
     * 注意：极罕见的类名恰好为单个基本类型字母（如 {@code I}）时会被一并跳过，
     * 产生漏报而非误报，符合「严格避免误报」的优先级。
     */
    private static boolean isArrayOrPrimitiveOwner(String owner) {
        if (owner.charAt(0) == '[') {
            return true;
        }
        return owner.length() == 1 && "VZBSCIJFD".indexOf(owner.charAt(0)) >= 0;
    }

    /**
     * Handle 标签是否为字段句柄（get/put field/static）。
     *
     * @param tag Handle 标签（{@link Opcodes#H_GETFIELD} 等）
     * @return 是否字段句柄
     */
    private static boolean isFieldHandle(int tag) {
        return tag == Opcodes.H_GETFIELD || tag == Opcodes.H_GETSTATIC
                || tag == Opcodes.H_PUTFIELD || tag == Opcodes.H_PUTSTATIC;
    }
}
