package com.paicli.mcp;

import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.notifications.NotificationRouter;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceCache;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.mcp.resources.McpResourceTool;
import com.paicli.policy.AuditLog;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.mcp.transport.StdioTransport;
import com.paicli.mcp.transport.StreamableHttpTransport;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class McpServerManager implements AutoCloseable {
    private static final Duration STARTUP_PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private final ToolRegistry toolRegistry;
    private final Path projectDir;
    private final McpConfigLoader configLoader;
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();
    private final McpResourceCache resourceCache = new McpResourceCache();

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir) {
        this(toolRegistry, projectDir, new McpConfigLoader(projectDir));
    }

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader) {
        this.toolRegistry = toolRegistry;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.configLoader = configLoader;
    }
    // 在启动MCP Server之前加载配置
    public void loadConfiguredServers() throws IOException {
        Map<String, McpServerConfig> configs = configLoader.load();
        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    public void startAll() {
        startAll(null);
    }

    public void startAll(PrintStream progressOut) {
        startAll(progressOut, null);
    }

    /**
     * Start all configured servers.
     *
     * <p>When {@code maxWait} is {@code null}, this method preserves the historical
     * blocking behavior and waits until every server reaches READY/ERROR. When a
     * bounded wait is supplied, unfinished servers continue starting on daemon
     * threads so the CLI can render the first prompt instead of being held hostage
     * by a slow stdio/http server.
     */
    // 启动所有MCP server实例的一个方法
    /**
     * 启动所有已配置的 MCP server。
     *
     * 流程：
     * 1. 过滤出所有未禁用（disabled=false）的 server 作为启动目标；
     * 2. 目标为空直接返回；
     * 3. 创建固定线程池（最多 8 线程，daemon 线程，命名 paicli-mcp-startup-N），
     *    避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool；
     * 4. 启动一个进度打印线程（progressPrinter），周期性输出启动进度；
     * 5. 每个 server 用 CompletableFuture.runAsync 提交到线程池并发启动（调 start(server)）；
     * 6. 等待全部启动完成：
     *    - maxWait 为空 → all.join() 无限阻塞等待（历史行为）；
     *    - maxWait 有值 → all.get(timeout) 限时等待，超时则打印"后台继续启动"提示，
     *      未完成的 server 留在 daemon 线程继续启动，CLI 先进入交互（不阻塞首屏）；
     * 7. finally 收尾：中断进度打印线程、关闭线程池（shutdown 不中断还在跑的启动线程）。
     *
     * @param progressOut 进度输出流，可为 null
     * @param maxWait     启动等待上限；null 表示无限等待
     */
    public void startAll(PrintStream progressOut, Duration maxWait) {
        List<McpServer> targets = servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) {
            return;
        }

        // 这些启动线程是守护线程（setDaemon(true)），JVM 退出时直接强制终止
        // MCP 子进程是 paicli JVM 进程的子进程，操作系统在父进程退出时通常也会回收子进程
        // 用专属 daemon executor，避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool 影响其他并发任务。
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(targets.size(), 8),
                r -> {
                    Thread t = new Thread(r, "paicli-mcp-startup-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        Thread progressPrinter = startProgressPrinter(targets, progressOut, STARTUP_PROGRESS_INTERVAL);
        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> start(server), executor))
                    .toList();
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
                // 如果用户没有设置超时,则一直阻塞等待,直到所有的server实例都启动完成
                all.join();
            } else {
                try {
                    all.get(Math.max(1, maxWait.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (Exception e) {
                    all.join();
                }
            }
        } finally {
            if (progressPrinter != null) {
                progressPrinter.interrupt();
            }
            // 拒绝执行新任务,但不会停止还在跑的线程
            executor.shutdown();
        }
    }

    private void printStartupTimeout(List<McpServer> targets, PrintStream out, Duration maxWait) {
        if (out == null) {
            return;
        }
        List<McpServer> stillStarting = targets.stream()
                .filter(server -> server.status() == McpServerStatus.STARTING)
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
        if (stillStarting.isEmpty()) {
            return;
        }
        String names = stillStarting.stream()
                .map(McpServer::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        long displaySeconds = Math.max(1, (long) Math.ceil(maxWait.toMillis() / 1000.0));
        out.printf("⚠️ MCP 启动超过 %ds，先进入 CLI；后台继续启动: %s%n",
                displaySeconds, names);
        out.println("   可用 /mcp 查看最新状态，或 /mcp logs <name> 查看日志。");
        out.flush();
    }

    private Thread startProgressPrinter(List<McpServer> targets, PrintStream out, Duration interval) {
        if (out == null || targets.isEmpty()) {
            return null;
        }
        Map<String, Instant> startedAt = new ConcurrentHashMap<>();
        targets.forEach(server -> startedAt.put(server.name(), Instant.now()));
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeUnit.MILLISECONDS.sleep(interval.toMillis());
                    List<McpServer> starting = targets.stream()
                            .filter(server -> server.status() == McpServerStatus.STARTING)
                            .sorted(Comparator.comparing(McpServer::name))
                            .toList();
                    if (starting.isEmpty()) {
                        continue;
                    }
                    for (McpServer server : starting) {
                        long waited = Duration.between(startedAt.get(server.name()), Instant.now()).toSeconds();
                        out.printf("   ⏳ %-16s %-6s 启动中...（已等待 %ds）%n",
                                server.name(), server.transportName(), waited);
                    }
                    out.flush();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "paicli-mcp-startup-progress");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public synchronized String restart(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已重启: " + name
                : "❌ MCP server 重启失败: " + name + " - " + server.errorMessage();
    }

    public synchronized String restartWithArgs(String name, List<String> args) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setArgs(args);
        return restart(name);
    }

    public McpServer server(String name) {
        return servers.get(name);
    }

    public synchronized String disable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(true);
        server.status(McpServerStatus.DISABLED);
        server.errorMessage(null);
        return "⏸️ MCP server 已禁用: " + name;
    }

    public synchronized String enable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "▶️ MCP server 已启用: " + name
                : "❌ MCP server 启用失败: " + name + " - " + server.errorMessage();
    }

    public String logs(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        List<String> lines = server.logs();
        if (lines.isEmpty()) {
            return "📭 MCP server 暂无 stderr 日志: " + name;
        }
        return String.join(System.lineSeparator(), lines);
    }

    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(java.util.Comparator.comparing(McpServer::name))
                .toList();
    }

    public String formatStatus() {
        StringBuilder sb = new StringBuilder("🔌 MCP Servers\n");
        if (servers.isEmpty()) {
            sb.append("  未配置 MCP server。配置文件: ~/.paicli/mcp.json 或 .paicli/mcp.json");
            return sb.toString();
        }
        for (McpServer server : servers()) {
            String status = switch (server.status()) {
                case READY -> "● ready";
                case STARTING -> "… starting";
                case DISABLED -> "○ disabled";
                case ERROR -> "✗ error";
            };
            String tools = server.status() == McpServerStatus.READY
                    ? server.tools().size() + (server.tools().size() == 1 ? " tool" : " tools")
                    : "—";
            String uptime = server.status() == McpServerStatus.READY ? "uptime " + formatDuration(server.uptime()) : "";
            String pid = server.processId() == null ? "" : "pid " + server.processId();
            String error = server.status() == McpServerStatus.ERROR && server.errorMessage() != null
                    ? server.errorMessage()
                    : "";
            sb.append(String.format("  %-14s %-11s %-6s %-9s %-10s %s %s%n",
                    server.name(), status, server.transportName(), tools, uptime, pid, error));
        }
        return sb.toString().trim();
    }

    public String startupSummary() {
        if (servers.isEmpty()) {
            return "🔌 MCP server：未配置（可创建 ~/.paicli/mcp.json 或 .paicli/mcp.json）";
        }
        long ready = servers.values().stream().filter(s -> s.status() == McpServerStatus.READY).count();
        int tools = servers.values().stream().mapToInt(s -> s.tools().size()).sum();
        StringBuilder sb = new StringBuilder("🔌 启动 MCP server（" + servers.size() + " 个）...\n");
        for (McpServer server : servers()) {
            if (server.status() == McpServerStatus.READY) {
                sb.append(String.format("   ✓ %-14s %-6s %3d 工具%n",
                        server.name(), server.transportName(), server.tools().size()));
            } else if (server.status() == McpServerStatus.DISABLED) {
                sb.append(String.format("   ○ %-14s %-6s disabled%n", server.name(), server.transportName()));
            } else if (server.status() == McpServerStatus.STARTING) {
                sb.append(String.format("   … %-14s %-6s starting%n", server.name(), server.transportName()));
            } else {
                sb.append(String.format("   ✗ %-14s %-6s 启动失败: %s%n",
                        server.name(), server.transportName(), server.errorMessage()));
            }
        }
        sb.append("   ").append(ready).append("/").append(servers.size())
                .append(" 就绪，共 ").append(tools).append(" 个 MCP 工具");
        return sb.toString();
    }

    public List<McpResourceDescriptor> resourceCandidates() {
        return resourceCache.all();
    }

    public String resourceIndexForPrompt() {
        List<McpResourceDescriptor> resources = resourceCache.all().stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::serverName)
                        .thenComparing(McpResourceDescriptor::uri))
                .limit(200)
                .toList();
        if (resources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## MCP Resources 索引（仅 URI / 描述，不含正文）\n\n");
        sb.append("长上下文模式下可参考以下资源索引判断是否需要读取 resource；需要正文时再调用对应 MCP resource 工具或使用用户显式 @-mention。\n\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- @").append(resource.serverName()).append(':').append(resource.uri());
            String displayName = resource.displayName();
            if (!displayName.equals(resource.uri())) {
                sb.append(" — ").append(displayName);
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("：").append(resource.description());
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" [").append(resource.mimeType()).append(']');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String resources(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<McpResourceDescriptor> resources = refreshResources(server);
            return McpClient.formatResources(resources);
        } catch (Exception e) {
            return "读取 MCP resources 失败: " + e.getMessage();
        }
    }

    public String prompts(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<String> prompts = server.client().listPrompts();
            if (prompts.isEmpty()) {
                return "📭 该 MCP server 暂无 prompts: " + serverName;
            }
            StringBuilder sb = new StringBuilder("🧩 MCP prompts - ").append(serverName).append('\n');
            for (String prompt : prompts) {
                sb.append("- ").append(prompt).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "读取 MCP prompts 失败: " + e.getMessage();
        }
    }

    public ResourceReadResult readResourceForMention(String serverName, String uri) throws IOException {
        McpServer server = servers.get(serverName);
        if (server == null) {
            throw new IOException("未找到 MCP server: " + serverName);
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            throw new IOException("MCP server 未就绪: " + serverName + " (" + server.status() + ")");
        }
        long start = System.nanoTime();
        String toolName = McpToolDescriptor.namespaced(serverName, McpResourceTool.READ_RESOURCE);
        String args = "{\"uri\":\"" + uri.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        try {
            if (resourceCache.isServerStale(serverName)) {
                refreshResources(server);
            }
            List<McpResourceContent> contents = server.client().readResource(uri);
            resourceCache.markResourceFresh(serverName, uri);
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.allowByMention(
                    toolName, args, elapsedMillis(start)));
            return ResourceReadResult.from(contents);
        } catch (Exception e) {
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.error(
                    toolName, args, e.getMessage(), elapsedMillis(start)));
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 启动单个 MCP server（在启动线程池里被并发调用）。
     *
     * 流程：
     * 1. 清理旧状态：unregisterTools 注销旧工具、close 关闭旧连接；
     * 2. 禁用检查：config.isDisabled() 则标记 DISABLED 直接返回；
     * 3. 标记 STARTING 并清空错误信息；
     * 4. configLoader.prepare(config)：展开 ${VAR} 环境变量、校验 transport（command/url 二选一）；
     * 5. createTransport(config)：按配置建立 stdio 或 Streamable HTTP 传输通道；
     * 6. new McpClient(name, transport)：创建 JSON-RPC 客户端；
     * 7. client.initialize()：发送 JSON-RPC initialize 握手；
     * 8. registerNotificationHandlers：注册通知处理器；
     * 9. buildToolList(server, client)：拉取工具列表（含 resources 资源转成的虚拟工具）；
     * 10. replaceTools(server, client, tools)：把工具注册进 ToolRegistry（registerMcpTool）；
     * 11. 保存 client / tools、记录启动时间、标记 READY；
     * 12. 任一步失败：close + 记录错误信息 + 标记 ERROR（单 server 失败不影响其他 server）。
     *
     * @param server 要启动的 MCP server
     */
    /**
     * 启动单个 MCP server（在启动线程池里被并发调用）。
     * 流程：清理旧状态 → 展开环境变量 → 建传输通道 → 握手 → 拉工具 → 注册 → 标记 READY。
     * 任一步失败：关闭连接 + 记录错误 + 标记 ERROR（单 server 失败不影响其他 server）。
     */
    private void start(McpServer server) {
        unregisterTools(server);   // ① 注销该 server 之前注册的所有工具（避免旧工具残留）
        server.close();            // ② 关闭旧的连接（restart 场景下先清理干净）
        if (server.config().isDisabled()) {   // ③ 若配置里标记为禁用
            server.status(McpServerStatus.DISABLED);   // 直接标记 DISABLED
            return;                // 不启动，直接返回
        }
        server.status(McpServerStatus.STARTING);   // ④ 标记状态为"启动中"
        server.errorMessage(null);                 // ⑤ 清空上一次的错误信息
        try {
            // 在单 server 启动路径里展开 ${VAR} 与校验 transport,
            // 单个失败仅标 ERROR，不会阻塞其他 server。
            configLoader.prepare(server.config());   // ⑥ 展开 ${VAR} 环境变量 + 校验 transport（command/url 二选一）
            McpTransport transport = createTransport(server.config());   // ⑦ 按配置创建 stdio 或 Streamable HTTP 传输通道
            McpClient client = new McpClient(server.name(), transport);  // ⑧ 创建 JSON-RPC 客户端（绑定传输通道）
            client.initialize();                  // ⑨ 发 JSON-RPC initialize 握手，拿到 capabilities
            registerNotificationHandlers(server, client);   // ⑩ 注册被动通知处理器（tools/list_changed 等）
            List<McpToolDescriptor> tools = buildToolList(server, client);   // ⑪ 拉取工具列表（含 resources 虚拟工具 + Schema 清洗）
            replaceTools(server, client, tools);  // ⑫ 把工具注册进 ToolRegistry（registerMcpTool）
            server.client(client);                // ⑬ 保存 client 引用（后续调用工具用）
            server.tools(tools);                  // ⑭ 保存工具列表（状态展示/热更新用）
            server.markStarted();                 // ⑮ 记录启动时间
            server.status(McpServerStatus.READY); // ⑯ 标记 READY（启动成功）
        } catch (Exception e) {                   // 任一步失败都进这里
            server.close();                       // 关闭连接
            server.errorMessage(e.getMessage());  // 记录错误信息
            server.status(McpServerStatus.ERROR); // 标记 ERROR（不影响其他 server）
        }
    }

    private List<McpToolDescriptor> buildToolList(McpServer server, McpClient client) throws IOException {
        List<McpToolDescriptor> tools = new ArrayList<>(client.listTools());
        if (client.supportsResources()) {
            List<McpResourceDescriptor> resources = client.listResources();
            resourceCache.put(server.name(), resources);
            tools.addAll(McpResourceTool.descriptors(server.name()));
        }
        validateNoDuplicateTools(server.name(), tools);
        return tools;
    }

    private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
        toolRegistry.replaceMcpToolOutputsForServer(server.name(), tools,
                descriptor -> isResourceVirtualTool(descriptor)
                        ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
                        : args -> invokeMcpToolOutput(client, descriptor, args));
    }

    private boolean isResourceVirtualTool(McpToolDescriptor descriptor) {
        return McpResourceTool.LIST_RESOURCES.equals(descriptor.name())
                || McpResourceTool.READ_RESOURCE.equals(descriptor.name());
    }

    private void registerNotificationHandlers(McpServer server, McpClient client) {
        NotificationRouter router = new NotificationRouter();
        router.on("notifications/tools/list_changed", ignored -> {
            try {
                List<McpToolDescriptor> tools = buildToolList(server, client);
                replaceTools(server, client, tools);
                server.tools(tools);
            } catch (Exception e) {
                server.errorMessage("tools/list_changed 处理失败: " + e.getMessage());
            }
        });
        router.on("notifications/resources/list_changed", ignored -> resourceCache.invalidateServer(server.name()));
        router.on("notifications/resources/updated", params -> {
            String uri = params.path("uri").asText("");
            if (!uri.isBlank()) {
                resourceCache.invalidateResource(server.name(), uri);
            }
        });
        client.onNotification(router);
    }

    private List<McpResourceDescriptor> refreshResources(McpServer server) throws IOException {
        List<McpResourceDescriptor> resources = server.client().listResources();
        resources = resources.stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::uri))
                .toList();
        resourceCache.put(server.name(), resources);
        return resources;
    }

    /**
     * MCP 工具执行入口：把 LLM 给的 JSON 参数透传给 server 的 tools/call，并把异常转成 LLM 可读字符串。
     * 提取成独立方法是为了让 server 维度的错误信息（serverName/toolName）在堆栈和日志里清晰可见。
     */
    private static ToolOutput invokeMcpToolOutput(McpClient client, McpToolDescriptor descriptor, String argumentsJson) {
        try {
            return client.callToolOutput(descriptor.name(), argumentsJson);
        } catch (Exception e) {
            return ToolOutput.text("MCP 工具调用失败 (" + descriptor.serverName() + "/" + descriptor.name() + "): "
                    + e.getMessage());
        }
    }

    private McpTransport createTransport(McpServerConfig config) throws IOException {
        if (config.isHttp()) {
            return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
        }
        return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir);
    }

    private void validateNoDuplicateTools(String serverName, List<McpToolDescriptor> tools) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (McpToolDescriptor tool : tools) {
            counts.merge(tool.name(), 1, Integer::sum);
        }
        List<String> duplicates = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count > 1) duplicates.add(name);
        });
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("MCP server " + serverName + " 返回重复工具名: " + duplicates);
        }
    }

    private void unregisterTools(McpServer server) {
        for (McpToolDescriptor tool : server.tools()) {
            toolRegistry.unregisterMcpTool(tool.namespacedName());
        }
        server.tools(List.of());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public record ResourceReadResult(String content, String mimeType) {
        static ResourceReadResult from(List<McpResourceContent> contents) {
            if (contents == null || contents.isEmpty()) {
                return new ResourceReadResult("", "text/plain");
            }
            StringBuilder text = new StringBuilder();
            String firstMimeType = null;
            for (McpResourceContent content : contents) {
                if (firstMimeType == null || firstMimeType.isBlank()) {
                    firstMimeType = content.mimeType();
                }
                if (content.isText()) {
                    text.append(content.text());
                } else {
                    text.append("[binary resource blob omitted, base64 length=")
                            .append(content.blob() == null ? 0 : content.blob().length())
                            .append(']');
                }
                text.append(System.lineSeparator());
            }
            return new ResourceReadResult(text.toString().trim(), firstMimeType);
        }
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h";
    }

    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            unregisterTools(server);
            server.close();
        }
    }
}
