package io.github.nanoforged.sourcesector.mapping.core;

/**
 * 中间名映射生成管线的运行时错误。
 * <p>
 * 用于输入校验失败（重复类、非法前缀）、类层次环等可确定判定的错误；
 * 文件 I/O 错误仍以受检异常上抛，由调用方决定处理方式。
 */
public class SourceSectorException extends RuntimeException {

    /**
     * 创建错误。
     *
     * @param message 错误描述
     */
    public SourceSectorException(String message) {
        super(message);
    }

    /**
     * 创建错误。
     *
     * @param message 错误描述
     * @param cause   根因
     */
    public SourceSectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
