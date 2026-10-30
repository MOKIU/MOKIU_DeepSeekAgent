package com.paicli.tool;

import java.nio.file.Path;

/**
 * 工具结果投影：一次工具输出按消费方切成不同视图。
 *
 * <p>{@code modelText} 进模型历史；{@code uiText} 给终端展示；{@code raw} 是原文引用，
 * 供 {@code read_tool_result} 按行回读。{@code schemaVersion} 用于后续字段演进。
 */
// 一次工具调用的"结果包裹"：同一份原文，切成给模型的、给界面的、和存硬盘的。
public record ToolResultProjection(
        // 工具参数字段的版本号,用来区分新旧数据
        int schemaVersion,
        // 一次工具调用的标识
        String toolUseId,

        String toolName,
        // 工具结果的压缩策略
        Strategy strategy,
        // 工具调用结果给模型看的
        String modelText,
        // 工具调用结果给终端看的
        String uiText,
        // 当工具执行结果被压缩后知道去哪找到压缩前的原文
        RawRef raw,
        // 量化的评测,如何压缩,压了多少,花了多久
        ProjectionMetrics metric
        ) {
    public static final int SCHEMA_VERSION = 1;
    // 初始化,避免模型文本和终端文本为空
    public ToolResultProjection {
        modelText = modelText == null ? "" : modelText;
        uiText = uiText == null ? "" : uiText;
    }
    // 根据是不是空来判断是否被压缩过
    public boolean truncated() {
        return raw != null;
    }
    // 压缩策略
    public enum Strategy {
        FULL,       // 没超阈值，原样
        HEAD_TAIL,  // 中等长度：留头 + 留尾 + 报错行
        DIGEST      // 超长：只给摘要 + 原文位置
    }
    // 未压缩工具执行结果溯源
    public record RawRef(
            Path path, // 原文存在哪个文件
            int bytes, // 文件多大
            int lines, // 文件行数
            String sha256){ // 内容指纹,防止读到被改过的文件
    }
    // 对于评测的量化对象
    public record ProjectionMetrics(
            int rawChars, // 原始字符串
            int rawLines, // 原始行数
            int modelChars, // 压缩后给模型的字符数
            int uiChars, // 给界面的字符数
            int estimatedRawTokens, // 估算原始字符数
            int estimatedModelTokens, // 估算现token
            long projectNanos) { // 投影耗时
    }
}
