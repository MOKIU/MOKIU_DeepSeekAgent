package com.paicli.hitl;

import com.paicli.browser.BrowserCheckResult;
import com.paicli.policy.AuditLog;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.paicli/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        // HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        // 浏览器工具的安全检查,检查是不是敏感页面,敏感页面直接拦掉不给审批机会
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return super.doExecuteTool(name, argumentsJson);
        }
        // 敏感页面上的写操作，不阻断，但强制每次都弹出审批框，不允许用"全部放行"偷懒,与上面是互斥的关系
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(name, argumentsJson, browserCheck.sensitiveNotice());
        }
        // 快捷全部放行工具，在必要的敏感写操作审批之后
        String mcpServer = ApprovalPolicy.mcpServerName(name);
        if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
            return super.doExecuteTool(name, argumentsJson);
        }

        return executeAfterExplicitApproval(name, argumentsJson, null);
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice) {
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null, null, sensitiveNotice);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, reason, elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "用户跳过", elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被跳过");
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数；父类执行路径会负责 allow audit
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.doExecuteTool(name, effectiveArgs);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
