package com.paicli.hitl;

import java.util.Set;

/**
 * 危险操作识别策略 - 基于静态规则判断哪些工具调用需要人工确认
 *
 * 设计原则：
 * - 读取类操作（read_file、list_dir、glob_files、grep_code、search_code）不需要确认，无副作用
 * - 写入/执行类操作（write_file、execute_command）需要确认，有潜在破坏性
 * - create_project 属于写入操作，默认需要确认
 * - revert_turn 会批量回写工作区文件，默认需要确认
 * - MCP 工具来自外部 server，默认都需要确认
 */
public class ApprovalPolicy {

    // 需要人工确认的工具集合
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project",
            "revert_turn"
    );

    private ApprovalPolicy() {
    }

    /**
     * 判断该工具调用是否需要人工确认。
     * 规则：在内置危险工具集合里，或以 mcp__ 前缀（外部 MCP 工具默认都要审）。
     */
    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);   // 内置危险工具 或 MCP 工具
    }

    /**
     * 获取危险等级描述（三级：高危/中危/安全），用于审批框展示。
     */
    public static String getDangerLevel(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";   // 执行命令：能改系统，最危险
            case "revert_turn" -> "🔴 高危";       // 回滚：批量覆盖工作区
            case "write_file" -> "🟡 中危";        // 写文件：覆盖内容
            case "create_project" -> "🟡 中危";    // 建项目：创建目录文件
            default -> isMcpTool(toolName) ? "🟡 MCP" : "🟢 安全";   // MCP 默认中危，其余安全
        };
    }

    /**
     * 获取危险操作的风险说明（给用户看的具体后果）。
     */
    public static String getRiskDescription(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "revert_turn" -> "将按 Side-Git 快照批量恢复工作区文件，可能覆盖当前未保存修改";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> isMcpTool(toolName)
                    ? "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务"
                    : "安全的只读操作";
        };
    }

    /**
     * 获取所有需要审批的工具名集合（用于测试和展示）。
     */
    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    /** 判断是否是 MCP 工具（以 mcp__ 前缀命名）。 */
    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    /**
     * 从 MCP 工具名里提取 server 名。
     * 命名格式是 mcp__{server}__{tool}，所以按 "__" 切三段，取中间那段。
     * 例：mcp__chrome-devtools__navigate_page → chrome-devtools
     */
    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;                            // 非 MCP 工具没有 server 名
        }
        String[] parts = toolName.split("__", 3);   // 按 "__" 切三段：["mcp", server, tool]
        return parts.length >= 2 ? parts[1] : null; // 取第二段（server 名）
    }
}
