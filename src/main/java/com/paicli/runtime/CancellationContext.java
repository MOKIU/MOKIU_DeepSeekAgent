package com.paicli.runtime;

import java.util.concurrent.atomic.AtomicReference;

public final class CancellationContext {
    private static final AtomicReference<CancellationToken> CURRENT = new AtomicReference<>();
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        CURRENT.set(token);
        LOCAL.set(token);
        return token;
    }
    // 检查当前运行上下文是否已被外部信号标记为“取消”:为“按 ESC 退出”这个动作提供支持的“状态桥梁”,作为安全检查点（Checkpoint）。在 AgentOrchestrator 的 run() 方法、runStep() 以及循环中随处可见 if (CancellationContext.isCancelled()) { return ... }。这确保当用户点击“停止”或系统触发中断时，正在执行的耗时代理任务（LLM 调用、步骤循环）能够快速感知并优雅退出，而不是阻塞等待直到完成。
    public static CancellationToken current() {
        CancellationToken token = LOCAL.get();
        return token == null ? CURRENT.get() : token;
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
        CURRENT.compareAndSet(token, null);
    }
}
