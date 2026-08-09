package io.github.nanoforged.sourcesector.util;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 子命令共享的参数校验与目录展开工具。
 * <p>
 * 确定性纪律：目录扫描结果按路径排序后并入，与文件系统返回顺序无关。
 */
public final class CliUtil {

    private CliUtil() {
    }

    /**
     * 汇总输入 jar：显式列表 + 目录扫描（排序）。空输入直接报参数错误。
     *
     * @param jars 显式 jar 列表
     * @param dirs 目录列表
     * @param spec 命令规格（错误上报用）
     * @return 汇总后的 jar 列表
     * @throws IOException 扫描失败
     */
    public static List<Path> resolveInputs(List<Path> jars, List<Path> dirs, CommandSpec spec) throws IOException {
        List<Path> all = resolveLibraries(jars, dirs, spec);
        if (all.isEmpty()) {
            throw new ParameterException(spec.commandLine(), "未指定输入 jar（--input 或 --input-dir）");
        }
        return all;
    }

    /**
     * 汇总库 jar（可空）。
     *
     * @param jars 显式 jar 列表
     * @param dirs 目录列表
     * @param spec 命令规格
     * @return 汇总后的 jar 列表（可能为空）
     * @throws IOException 扫描失败
     */
    public static List<Path> resolveLibraries(List<Path> jars, List<Path> dirs, CommandSpec spec) throws IOException {
        List<Path> all = new ArrayList<>(jars);
        all.addAll(expandJarDirs(dirs, spec));
        return all;
    }

    private static List<Path> expandJarDirs(List<Path> dirs, CommandSpec spec) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                throw new ParameterException(spec.commandLine(), "目录不存在: " + dir);
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .forEach(jars::add);
            }
        }
        return jars;
    }

    /**
     * 校验并归一化中间名包前缀（点分自动转内部名斜杠形式）。
     *
     * @param prefix 前缀（可空）
     * @param spec   命令规格
     * @return 归一化前缀；未指定返回 {@code null}
     */
    public static String validatePrefix(String prefix, CommandSpec spec) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        String normalized = prefix.replace('.', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new ParameterException(spec.commandLine(), "前缀不能为空");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.indexOf(';') >= 0 || segment.indexOf('[') >= 0
                    || segment.indexOf('(') >= 0) {
                throw new ParameterException(spec.commandLine(),
                        "非法前缀（应为包路径，如 com/example/out）: " + prefix);
            }
        }
        return normalized;
    }
}
