package com.paicli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端 HITL 审批处理器
 *
 * 在终端展示审批请求，等待用户键盘输入后返回决策。
 *
 * 支持的交互选项：
 *   y / Enter - 批准本次操作
 *   a         - 批准本次会话所有后续同类危险操作（工具维度；MCP 支持 server 维度）
 *   n         - 拒绝本次操作
 *   s         - 跳过本步骤（SKIPPED）
 *   m         - 修改参数后执行（进入参数输入模式）
 *
 * 并发安全：
 *   requestApproval 方法整体 synchronized，确保多 Agent 并行场景下同一时刻只有一个
 *   审批提示活跃，避免 stdout 串扰与 stdin 争抢。
 */
public class TerminalHitlHandler implements HitlHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile boolean enabled;

    // 本次会话中已批准"全部放行"的集合（并发安全）
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();

    private final BufferedReader in;
    private final PrintStream out;

    public TerminalHitlHandler(boolean enabled) {
        this(enabled,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }

    /**
     * 包可见构造器：允许测试注入自定义 stdin / stdout。
     */
    TerminalHitlHandler(boolean enabled, BufferedReader in, PrintStream out) {
        this.enabled = enabled;
        this.in = in;
        this.out = out;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 展示审批请求并收集决策；整体 synchronized 以便并发 Agent 调用时序列化。
     */
    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());   // 提取 server 名（MCP 工具才有）
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();   // 是否敏感页面写操作
        if (!sensitivePerCall && isApprovedAllByTool(request.toolName())) {   // 非敏感 + 已按工具全部放行
            out.println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();   // 直接自动通过，不弹框
        }
        if (!sensitivePerCall && isApprovedAllByServer(mcpServer)) {   // 非敏感 + 已按 server 全部放行
            out.println("  [HITL] MCP server " + mcpServer + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAllByServer();   // 直接自动通过
        }

        // 显著的视觉分隔符，避免审批框被误认为属于上游的"回复"区
        out.println();
        out.println("────────── ⚠️  HITL 审批请求 ──────────");   // 审批框标题
        if (sensitivePerCall) {
            out.println("⚠️  " + request.sensitiveNotice());   // 敏感页面的额外警示
        }
        out.println(request.toDisplayText());   // 打印工具名 + 危险等级 + 参数

        return promptUntilDecision(request);   // 进入交互循环，等用户按键
    }

    /**
     * 主交互循环：无法识别的输入会重新提示而非默认放行（fail-safe）。
     */
    private ApprovalResult promptUntilDecision(ApprovalRequest request) {
        for (int attempt = 0; attempt < 5; attempt++) {   // 最多重试 5 次（无效输入重新提示）
            out.println();
            boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
            if (sensitivePerCall) {
                out.println("请选择操作：[y/Enter] 批准本次  [n] 拒绝  [s] 跳过  [m] 修改参数");   // 敏感页不提供"全部放行"
            } else {
                out.println("请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            }
            out.print("> ");
            out.flush();

            String input;
            try {
                input = in.readLine();   // 读用户按键
            } catch (IOException e) {
                out.println("  [HITL] 读取用户输入失败，保守处理为拒绝");   // 读失败 → 保守拒绝（fail-safe）
                return ApprovalResult.reject("读取输入失败: " + e.getMessage());
            }
            if (input == null) {
                out.println("  [HITL] 输入流已关闭，保守处理为拒绝");   // 流关闭 → 保守拒绝
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();   // 规范化输入（去空白 + 小写）

            // Enter 或 y 等价于批准
            if (normalized.isEmpty() || normalized.equals("y")) {
                out.println("  已批准");
                return ApprovalResult.approve();   // 批准本次
            }
            switch (normalized) {
                case "a" -> {   // 全部放行
                    if (sensitivePerCall) {
                        out.println("  敏感页面操作不支持全部放行，请选择 y/n/s/m");   // 敏感页禁止全部放行
                        continue;   // 重新提示
                    }
                    return promptApproveAllScope(request);   // 进一步选范围（工具级 or server 级）
                }
                case "n" -> {   // 拒绝
                    out.print("  拒绝原因（可直接回车跳过）：");
                    out.flush();
                    String reason;
                    try {
                        reason = in.readLine();   // 读拒绝原因（可空）
                    } catch (IOException e) {
                        reason = "";
                    }
                    return ApprovalResult.reject(reason == null ? "" : reason.trim());
                }
                case "s" -> {   // 跳过
                    out.println("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {   // 修改参数
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) {
                        return modified;   // 修改成功，返回
                    }
                    // 修改失败（JSON 非法等）时回到主菜单重新提示
                }
                default -> out.println("  ❓ 无法识别的选项：'" + input + "'，请输入 y/a/n/s/m 之一（Enter 等价于 y）");   // 非法输入提示
            }
        }
        out.println("  [HITL] 连续多次无效输入，保守处理为拒绝");   // 5 次无效 → 保守拒绝
        return ApprovalResult.reject("连续多次无效输入");
    }

    private ApprovalResult promptApproveAllScope(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());   // 提取 server 名
        if (mcpServer == null || mcpServer.isBlank()) {   // 非 MCP 工具（内置工具）
            approvedAllByTool.add(request.toolName());    // 直接按工具维度放行
            out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
            return ApprovalResult.approveAll();
        }

        // MCP 工具：让用户选放行范围（本工具 / 整个 server）
        out.println("  全部放行范围：");
        out.println("  [tool / Enter] 仅本工具 " + request.toolName());
        out.println("  [server]       整个 MCP server " + mcpServer + "（连续浏览器操作推荐）");
        out.print("> ");
        out.flush();
        String scope;
        try {
            scope = in.readLine();   // 读范围选择
        } catch (IOException e) {
            out.println("  读取范围失败，默认按工具维度放行");
            scope = "";
        }
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        if ("server".equals(normalized) || "s".equals(normalized)) {   // 选 server 维度
            approvedAllByServer.add(mcpServer);   // 记录整个 server 放行
            out.println("  已批准，后续 MCP server " + mcpServer + " 的工具调用将自动通过");
            return ApprovalResult.approveAllByServer();
        }
        approvedAllByTool.add(request.toolName());   // 默认工具维度放行
        out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
        return ApprovalResult.approveAll();
    }

    /**
     * 修改参数子流程：验证用户输入为合法 JSON；非法则返回 null 让主循环重新提示。
     */
    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.println("  当前参数：" + request.arguments());   // 显示原参数
        out.print("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        out.flush();

        String modified;
        try {
            modified = in.readLine();   // 读新参数
        } catch (IOException e) {
            out.println("  读取失败，回到主菜单");
            return null;   // 返回 null → 主循环重新提示
        }
        if (modified == null || modified.isBlank()) {   // 空输入
            out.println("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();   // 退回用原始参数批准
        }

        String trimmed = modified.trim();
        try {
            MAPPER.readTree(trimmed);   // 校验是不是合法 JSON
        } catch (Exception e) {
            out.println("  ❌ 修改后的参数不是合法 JSON：" + e.getMessage());
            return null;  // 非法 → 回到主菜单
        }
        return ApprovalResult.modify(trimmed);   // 合法 → 返回"修改参数后执行"
    }

    /**
     * 清除本次会话中积累的"全部放行"记录
     * 在 /clear 或新会话开始时调用
     */
    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
        approvedAllByServer.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllByServer.remove(serverName);
        }
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }
}
