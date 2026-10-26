package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.browser.BrowserAuditMetadata;
import com.paicli.browser.BrowserConnectivityCheck;
import com.paicli.browser.BrowserGuard;
import com.paicli.browser.BrowserMode;
import com.paicli.browser.BrowserSession;
import com.paicli.browser.SensitivePagePolicy;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.HitlHandler;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.hitl.SwitchableHitlHandler;
import com.paicli.hitl.RendererHitlHandler;
import com.paicli.hitl.TerminalHitlHandler;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryEntry;
import com.paicli.render.Renderer;
import com.paicli.render.RendererFactory;
import com.paicli.render.StatusInfo;
import com.paicli.render.inline.InlineRenderer;
import com.paicli.image.ClipboardImage;
import com.paicli.mcp.McpServer;
import com.paicli.mcp.McpServerManager;
import com.paicli.mcp.McpServerStatus;
import com.paicli.mcp.mention.AtMentionExpander;
import com.paicli.plan.ExecutionPlan;
import com.paicli.rag.CodeIndex;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.policy.AuditLog;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.CodeRelation;
import com.paicli.rag.SearchResultFormatter;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.task.DurableTaskManager;
import com.paicli.runtime.task.TaskCommandFormatter;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SnapshotService;
import com.paicli.snapshot.TurnSnapshot;
import com.paicli.skill.SkillRegistry;
import com.paicli.tool.ToolRegistry;
import com.paicli.util.AnsiStyle;
import com.paicli.wechat.IlinkClient;
import com.paicli.wechat.WechatAccount;
import com.paicli.wechat.WechatAccountStore;
import com.paicli.wechat.WechatCommandMain;
import com.paicli.wechat.WechatLoginResult;
import com.paicli.wechat.WechatMessageLoop;
import com.paicli.wechat.WechatQrLogin;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Reference;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.AttributedString;
import org.jline.widget.AutosuggestionWidgets;
import org.jline.widget.AutopairWidgets;
import org.jline.console.CmdDesc;
import org.jline.keymap.KeyMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * PaiCLI v16.1.0 - Terminal-First Agent IDE
 * 支持 ReAct、Plan-and-Execute、Memory、RAG、Multi-Agent、HITL、并行工具调用、多模型切换、MCP、CDP 会话复用
 * 第 15 期新增：Skill 系统（三层加载 + load_skill 工具 + SkillContextBuffer 注入）、内置 web-access skill
 * 第 16 期新增：TUI 界面（Lanterna 3）、文件树浏览、代码高亮、对话历史可视化、配置管理面板
 * 第 16.1 期形态修正：抽出 Renderer 接口 + 三个实现（inline/lanterna/plain），默认形态切换为 inline 流式 TUI（Claude Code 风格）
 *   - inline 流式：prompt 下方 inline 状态区、行内可折叠工具块、行内 git diff、单字符 HITL 提示、命令 palette
 *   - lanterna：保留 phase-16 全屏窗口（向后兼容 PAICLI_TUI=true）
 *   - plain：纯 println 兜底
 * HITL 增强：路径围栏（PathGuard）、命令快速拒绝（CommandGuard）、操作审计链（AuditLog）—— 见 com.paicli.policy
 */
public class Main {
    private static final String VERSION = "16.1.0";
    private static final String ENV_FILE = ".env";
    private static final String LOG_DIR_PROPERTY = "paicli.log.dir";
    private static final String LOG_LEVEL_PROPERTY = "paicli.log.level";
    private static final String LOG_MAX_HISTORY_PROPERTY = "paicli.log.maxHistory";
    private static final String LOG_MAX_FILE_SIZE_PROPERTY = "paicli.log.maxFileSize";
    private static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "paicli.log.totalSizeCap";
    private static final String HISTORY_FILE_PROPERTY = "paicli.history.file";
    private static final String HISTORY_SIZE_PROPERTY = "paicli.history.size";
    private static final String HISTORY_FILE_SIZE_PROPERTY = "paicli.history.fileSize";
    private static final String DEFAULT_HISTORY_FILE_NAME = "input.history";
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    private static final String ARROW_UP = "[A";
    private static final String ARROW_DOWN = "[B";
    private static final String APP_ARROW_UP = "OA";
    private static final String APP_ARROW_DOWN = "OB";
    private static final Pattern SENSITIVE_FLAG_VALUE = Pattern.compile(
            "(?i)(--?(?:api[_-]?key|authorization|password|passwd|secret|token)\\s+)(\\S+)");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|authorization|password|passwd|secret|token)\\s*[=:]\\s*)(\\S+)");
    private static final int CTRL_O = 15;
    private static final String DEFAULT_CHROME_DEVTOOLS_MCP_JSON = """
            {
              "mcpServers": {
                "chrome-devtools": {
                  "command": "npx",
                  "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
                }
              }
            }
            """;

    enum EscapeSequenceType {
        STANDALONE_ESC,
        BRACKETED_PASTE,
        CONTROL_SEQUENCE,
        OTHER
    }

    private record PromptInput(String text, boolean canceled) {
        static PromptInput submitted(String text) {
            return new PromptInput(text, false);
        }

        static PromptInput canceledInput() {
            return new PromptInput("", true);
        }
    }

    private record PrefillResult(String seedBuffer, boolean canceled, boolean submitted) {
        static PrefillResult canceledInput() {
            return new PrefillResult("", true, false);
        }

        static PrefillResult submittedInput() {
            return new PrefillResult("", false, true);
        }

        static PrefillResult seed(String seedBuffer) {
            return new PrefillResult(seedBuffer, false, false);
        }
    }

    private record KeyReadResult(Integer key, boolean ignoredControlSequence) {
        static KeyReadResult keyPressed(int key) {
            return new KeyReadResult(key, false);
        }

        static KeyReadResult ignoredSequence() {
            return new KeyReadResult(null, true);
        }

        static KeyReadResult unavailable() {
            return new KeyReadResult(null, false);
        }
    }

    private record StartupScreenInfo(
            String model,
            String provider,
            long mcpReady,
            int mcpTotal,
            int mcpTools,
            int skillsEnabled,
            int skillsTotal,
            String note
    ) {
    }

    public static void main(String[] args) {
        // ============ 阶段 0：入口分流 ============
        // 根据启动参数决定走哪条路径：微信通道 / Runtime API / 交互式 CLI
        configureAwtForCli();   // 配置 AWT 无头模式（避免终端程序在 macOS 触发 GUI）
        if (WechatCommandMain.isWechatCommand(args)) {   // 判断是否为 wechat 子命令（如 paicli wechat start）
            configureLogging();   // 初始化日志系统
            int code = WechatCommandMain.run(args);   // 走微信通道入口，返回退出码
            if (code != 0) {   // 退出码非 0 表示出错
                System.exit(code);   // 直接以该码退出进程
            }
            return;   // 微信命令处理完，正常返回
        }
        if (isRuntimeServeCommand(args)) {   // 判断是否为 serve --http（Runtime API 模式）
            configureLogging();   // 初始化日志
            startRuntimeApiAndBlock(args);   // 启动 Runtime API 服务器并阻塞
            return;   // 处理完返回
        }

        configureLogging();   // 进入交互式 CLI 主路径，初始化日志

        // ============ 阶段 1：配置与 LLM 客户端加载 ============
        PaiCliConfig config = PaiCliConfig.load();   // 加载 ~/.paicli/config.json 配置
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);   // 按配置创建 LLM 客户端
        if (llmClient == null) {   // 创建失败 = 没有可用 API Key
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 GLM_API_KEY、DEEPSEEK_API_KEY、STEP_API_KEY、KIMI_API_KEY、FREELLMAPI_API_KEY 或 XFYUN_MAAS_API_KEY");
            System.exit(1);   // 退出进程（退出码 1）
        }
        AtomicReference<LlmClient> llmClientRef = new AtomicReference<>(llmClient);   // 原子引用包装，供后台任务/线程安全切换模型

        // ============ 阶段 2：终端创建 ============
        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {   // 创建 JLine 终端（try-with-resources 自动关闭）
            refreshTerminalColumns(terminal);   // 读取并刷新终端列宽（供表格/状态栏布局用）
            TerminalHitlHandler terminalHitlHandler = new TerminalHitlHandler(false);   // 创建"终端版"人工审批处理器（初始关闭）

            // ============ 阶段 3：安全 / 浏览器 / MCP 初始化 ============
            SwitchableHitlHandler hitlHandler = new SwitchableHitlHandler(terminalHitlHandler);   // 可切换的 HITL 处理器（终端版/渲染版间切换）
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);   // HITL 工具注册表（危险工具拦截层）
            BrowserSession browserSession = new BrowserSession();   // 浏览器会话状态（shared/isolated 模式）
            BrowserConnectivityCheck browserConnectivityCheck = new BrowserConnectivityCheck();   // 浏览器 CDP 连通性探活
            hitlToolRegistry.setBrowserGuard(new BrowserGuard(browserSession, new SensitivePagePolicy()));   // 注入浏览器守卫 + 敏感页策略
            McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));   // 创建 MCP server 管理器（项目根 = 当前目录）
            AtomicReference<SkillRegistry> skillRegistryRef = new AtomicReference<>();   // Skill 注册表原子引用（稍后填充）
            hitlToolRegistry.setBrowserConnector(new com.paicli.browser.BrowserConnector() {   // 给 HITL 注入浏览器连接器（匿名实现）
                @Override
                public String status() {   // status 操作
                    return handleBrowserCommand("status", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String connectDefault() {   // 默认连接操作
                    return handleBrowserCommand("connect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String disconnect() {   // 断开操作
                    return handleBrowserCommand("disconnect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }
            });

            // ============ 阶段 4：LineReader 输入组件初始化 ============
            LineReader lineReader = LineReaderBuilder.builder()   // JLine 输入行构建器
                    .terminal(terminal)   // 绑定终端
                    .history(new PaiCliHistory())   // 输入历史（持久化到 ~/.paicli/history/）
                    .completer(new PaiCliCompleter(mcpServerManager::resourceCandidates,
                            () -> skillRegistryRef.get() == null ? List.of() : skillRegistryRef.get().allSkills()))   // Tab 补全器（slash 命令/MCP resource/skill/路径）
                    .highlighter(new PaiCliHighlighter())   // 实时高亮（slash/敏感词/危险命令着色）
                    .build();   // 构建 LineReader
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);   // 支持括号粘贴（多行/大段粘贴）
            lineReader.option(LineReader.Option.AUTO_LIST, true);   // 补全时自动列出候选
            lineReader.option(LineReader.Option.AUTO_MENU, true);   // 自动菜单
            configureHistory(lineReader, Path.of(System.getProperty("user.home")));   // 配置输入历史文件
            configureSlashCommandHint(lineReader);   // 配置 / 命令提示
            configureJLineInteractiveWidgets(lineReader);   // 绑定交互 widget（ESC/Ctrl 等）

            // ============ 阶段 5：Renderer 渲染器初始化 ============
            // JLine-first：启动输出、命令输出、Agent 流式内容都走同一条 Renderer.stream() 通道。
            // inline 首屏要挂到 LineReader 首次初始化回调里，避免在 readLine 接管屏幕前用裸输出抢光标。
            Renderer renderer = RendererFactory.create(RendererFactory.resolveMode(), terminal);   // 按 PAICLI_RENDERER 创建渲染器（inline/lanterna/plain）
            RendererHitlHandler rendererHitl = new RendererHitlHandler(renderer, hitlHandler.isEnabled());   // 渲染器版审批处理器
            hitlHandler.setDelegate(rendererHitl);   // 让 HITL 走渲染器渲染
            if (renderer instanceof InlineRenderer inline) {   // 如果是 inline 渲染器
                inline.bindLineReader(lineReader);   // 绑定 LineReader（让输出显示在输入行上方）
            }
            PrintStream ui = renderer.stream();   // 统一输出流（所有输出走这里）
            renderer.start();   // 启动渲染器
            renderer.updateStatus(statusInfo(llmClient, hitlHandler, "idle", mcpServerManager, null));   // 更新状态栏为 idle

            // ============ 阶段 6：MCP 启动 ============
            String startupNote = "";   // 启动提示信息（首屏展示）
            try {
                McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(Path.of(System.getProperty("user.home")));   // 确保 ~/.paicli/mcp.json 存在（没有就建 chrome-devtools 默认配置）
                if (!bootstrapResult.message().isBlank()) {   // 有提示信息
                    startupNote = bootstrapResult.message();   // 记录
                }
                // 加载的是"MCP server 怎么接入"的配置（stdio 的 command/args/env，或 HTTP 的 url/headers，以及是否禁用），来源是用户级+项目级 mcp.json 加内置 step_search，合并后转成内存里的 McpServer 对象
                mcpServerManager.loadConfiguredServers();   // 加载 MCP server 配置
                mcpServerManager.startAll(ui, mcpStartupWait());   // 启动所有 MCP server（最多等 8 秒）
                Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close, "paicli-mcp-shutdown"));   // 注册关闭钩子，退出时关 MCP
            } catch (Exception e) {
                startupNote = "MCP 初始化失败: " + e.getMessage();   // MCP 失败不阻塞主流程，只记录提示
            }
            // ============ 阶段 7：@ 引用展开器 ============
            AtMentionExpander mentionExpander = new AtMentionExpander(mcpServerManager);   // MCP resource 的 @server:uri 展开
            LocalPathMentionExpander localPathMentionExpander = new LocalPathMentionExpander(Path.of("."));   // 本地 @path 展开

            // ============ 阶段 8：Skill 系统初始化 ============
            Path home = Path.of(System.getProperty("user.home"));   // 用户主目录
            Path skillsCacheDir = home.resolve(".paicli/skills-cache");   // 内置 skill 解压缓存目录
            Path userSkillsDir = home.resolve(".paicli/skills");   // 用户级 skill 目录
            Path projectSkillsDir = Path.of(".paicli/skills").toAbsolutePath();   // 项目级 skill 目录
            try {
                new com.paicli.skill.SkillBuiltinExtractor(skillsCacheDir).extractAll();   // 解压 jar 内置 skill 到缓存
            } catch (Exception e) {
                startupNote = appendStartupNote(startupNote, "内置 skill 解压失败: " + e.getMessage());   // 解压失败只提示，不阻塞
            }
            com.paicli.skill.SkillStateStore skillStateStore = new com.paicli.skill.SkillStateStore(home.resolve(".paicli/skills.json"));   // 启用状态存储
            com.paicli.skill.SkillRegistry skillRegistry = new com.paicli.skill.SkillRegistry(
                    skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore);   // 三层 skill 注册表
            skillRegistry.reload();   // 加载三层 skill
            skillRegistryRef.set(skillRegistry);   // 填充原子引用
            com.paicli.skill.SkillContextBuffer skillContextBuffer = new com.paicli.skill.SkillContextBuffer();   // skill 注入缓冲区
            hitlToolRegistry.setSkillRegistry(skillRegistry);   // 注入到 HITL 注册表
            hitlToolRegistry.setSkillContextBuffer(skillContextBuffer);   // 注入 buffer

            // ============ 阶段 9：Agent / 后台任务 / 微信初始化 ============
            Agent reactAgent = new Agent(llmClient, hitlToolRegistry);   // 创建 ReAct Agent（核心）
            reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);   // 注入 MCP resource 索引供应
            reactAgent.setSkillRegistry(skillRegistry);   // 注入 skill 注册表
            reactAgent.setSkillContextBuffer(skillContextBuffer);   // 注入 skill buffer
            DurableTaskManager taskManager = openTaskManager(llmClientRef);   // 打开后台任务管理器（SQLite 队列）
            taskManager.start();   // 启动 worker 池
            Runtime.getRuntime().addShutdownHook(new Thread(taskManager::close, "paicli-task-shutdown"));   // 退出时关任务管理器
            WechatRuntimeController wechatRuntime = new WechatRuntimeController(renderer);   // 微信通道运行时控制器
            Runtime.getRuntime().addShutdownHook(new Thread(wechatRuntime::stop, "paicli-wechat-shutdown"));   // 退出时停微信
            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));   // 刷新状态栏
            StartupScreenInfo startupScreenInfo = startupScreenInfo(llmClient, mcpServerManager, skillRegistry, startupNote);   // 组装开屏信息
            if (renderer instanceof InlineRenderer inline) {   // inline 模式
                inline.installStartupScreen(startupScreenLines(startupScreenInfo));   // 首屏挂到 LineReader 首次回调
            } else {
                printStartupScreen(ui, startupScreenInfo);   // 其它渲染器直接打印首屏
            }
            boolean nextTaskUsePlanMode = false;   // "下一条任务走 Plan"标志
            boolean nextTaskUseTeamMode = false;   // "下一条任务走 Team"标志

            // ============ 阶段 10：TUI / CLI 分支判断 ============
            // 旧 PAICLI_TUI=true 路径仍走 Lanterna 全屏 TUI（Day 5 后由 LanternaRenderer 接管）。
            if (com.paicli.tui.TuiBootstrap.shouldUseTui(terminal)) {   // 判断是否用 Lanterna 全屏 TUI（旧 PAICLI_TUI=true）
                try {
                    com.paicli.tui.TuiBootstrap.launch(config, llmClient, reactAgent, hitlHandler);   // 启动全屏 TUI
                    return;  // TUI 启动成功，不进入 CLI 循环
                } catch (Exception e) {
                    hitlHandler.setDelegate(terminalHitlHandler);   // TUI 失败，切回终端审批
                    System.err.println("❌ TUI 启动失败，降级到 CLI: " + e.getMessage());   // 提示降级
                    e.printStackTrace();   // 打印堆栈
                    // 降级到 CLI 继续执行
                }
            }

            // ============ 阶段 11：renderer 绑定 + 快捷键 ============
            reactAgent.setRenderer(renderer);   // 给 Agent 注入渲染器（流式输出走它）
            reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled);   // 注入 HITL 状态供应
            reactAgent.getToolRegistry().setWriteFileObserver(   // 注册 write_file 观察者
                    (path, ba) -> renderer.appendDiff(path, ba[0], ba[1]));   // 写文件时渲染行内 diff

            // Day 3：inline 模式绑 Ctrl+O 到 BlockRegistry.toggleLast 实现折叠块展开/收起
            boolean spaciousPrompt = false;   // 是否用宽松输入提示
            if (renderer instanceof InlineRenderer inline) {   // inline 模式
                bindCtrlOToFoldableBlocks(lineReader, inline);   // 绑定 Ctrl+O 展开折叠块
            }
            spaciousPrompt = defaultSpaciousPrompt(spaciousPrompt);   // 决定输入提示样式
            bindCtrlVToClipboardImage(lineReader);   // 绑定 Ctrl+V 粘贴剪贴板图片
            bindEscToClearInput(lineReader);   // 绑定 ESC 清空输入

            // ============ 阶段 12：交互主循环 ============
            while (true) {   // 交互主循环，直到 /exit 或 Ctrl+D 才退出
                refreshTerminalColumns(terminal);   // 每轮刷新终端列宽
                PromptInput promptInput;   // 本轮输入包装对象
                try {
                    promptInput = readPromptInput(terminal, lineReader, renderer,   // 读取输入（含提示符）
                            nextTaskUsePlanMode || nextTaskUseTeamMode, spaciousPrompt);   // 显示当前模式提示
                } catch (UserInterruptException e) {
                    continue;  // Ctrl+C 中断 → 跳过本轮
                } catch (EndOfFileException e) {
                    break;  // Ctrl+D → 退出循环
                }
                if (renderer instanceof InlineRenderer inline) {
                    inline.clearAcceptedInput(promptInput.text());   // 清掉已提交的输入
                }

                if (promptInput.canceled()) {   // 用户按 ESC 取消（比如取消了待执行的 plan）
                    if (nextTaskUsePlanMode) {   // 有待执行 plan
                        nextTaskUsePlanMode = false;   // 复位标志
                        ui.println("↩️ 已取消待执行的 Plan-and-Execute，回到默认 ReAct。\n");
                    }
                    if (nextTaskUseTeamMode) {   // 有待执行 team
                        nextTaskUseTeamMode = false;   // 复位标志
                        ui.println("↩️ 已取消待执行的 Multi-Agent，回到默认 ReAct。\n");
                    }
                    continue;   // 进入下一轮
                }

                String input = promptInput.text().trim();   // 取输入文本并去首尾空白

                if (input.isEmpty()) {   // 空输入
                    continue;   // 跳过
                }

                CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);   // 解析斜杠命令
                boolean submittedInputRendered = false;   // 标记"输入回显是否已渲染"
                if (command.type() != CliCommandParser.CommandType.NONE) {   // 是斜杠命令
                    renderer.beginTurn();   // 开始一轮
                    printSubmittedInput(renderer, ui, input);   // 回显原始输入（> 开头暗色块）
                    submittedInputRendered = true;   // 已渲染
                }
                switch (command.type()) {   // 按命令类型分发
                    case UNKNOWN_COMMAND -> {   // 未知斜杠命令：直接报错，不回退给 Agent
                        ui.println("❌ 未知命令: " + command.payload());
                        printSlashCommandHelp(ui);
                        continue;
                    }
                    case EXIT -> {   // /exit 退出程序
                        ui.println("\n👋 再见!");
                        wechatRuntime.stop();
                        renderer.close();
                        return;
                    }
                    case CANCEL -> {   // /cancel 空闲时提示
                        ui.println("当前没有正在运行的任务。\n");
                        continue;
                    }
                    case CLEAR -> {   // /clear 清对话历史 + 清"全部放行"，长期记忆保留
                        reactAgent.clearHistory();
                        hitlHandler.clearApprovedAll();
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        ui.println("🗑️ 当前对话历史已清空，长期记忆保持不变\n");
                        continue;
                    }
                    case COMPACT -> {   // /compact 手动压缩历史上下文
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "compacting"));
                        boolean activityPanel = renderer.supportsActivityPanel();   // 是否支持活动面板
                        if (activityPanel) {
                            renderer.beginActivity("Compacting conversation", "正在整理早期对话并生成摘要");   // 显示压缩中
                        } else {
                            ui.println("⏳ 压缩中，等一下下哦...\n");
                        }
                        Agent.CompactionResult result;
                        try {
                            result = reactAgent.compactHistoryNow();   // 立即压缩（跳过阈值判断）
                        } finally {
                            if (activityPanel) {
                                renderer.endActivity();   // 结束活动面板
                            }
                            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));   // 状态回 idle
                        }
                        if (result.error() != null && !result.error().isBlank()) {
                            ui.println("❌ 手动压缩失败: " + result.error() + "\n");
                        } else if (result.compacted()) {
                            ui.printf("📦 已手动压缩历史上下文: %,d -> %,d tokens%n%n",
                                    result.beforeTokens(), result.afterTokens());   // 打印压缩前后 token 对比
                        } else {
                            ui.println("📭 当前没有需要压缩的历史上下文\n");
                        }
                        continue;
                    }
                    case HISTORY_CLEAR -> {   // /history clear 清输入历史
                        clearLineReaderHistory(lineReader);
                        ui.println("🧹 输入历史已清空\n");
                        continue;
                    }
                    case INIT_PROJECT_MEMORY -> {   // /init 生成项目级 PAI.md
                        String payload = command.payload();
                        boolean force = payload != null && payload.trim().equalsIgnoreCase("--force");   // 是否 --force
                        if (payload != null && !payload.isBlank() && !force) {
                            ui.println("❌ 未知 /init 参数: " + payload);
                            ui.println("   用法: /init 或 /init --force\n");
                            continue;
                        }
                        try {
                            ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(
                                    Path.of(reactAgent.getToolRegistry().getProjectPath()), force);   // 生成 PAI.md
                            if (result.written()) {
                                ui.println("✅ " + result.message());
                                ui.println("   路径: " + result.path());
                                ui.println("   这份 PAI.md 会在后续 system prompt 的 Project Context 中注入。\n");
                            } else {
                                ui.println("ℹ️ " + result.message());
                                ui.println("   路径: " + result.path() + "\n");
                            }
                        } catch (IOException e) {
                            ui.println("❌ 生成 PAI.md 失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case CONTEXT_STATUS -> {   // /context 显示上下文状态
                        ui.println("📋 上下文状态：");
                        ui.println(reactAgent.getContextStatus());
                        ui.println();
                        continue;
                    }
                    case MEMORY_STATUS -> {   // /memory 显示记忆系统状态
                        ui.println("📋 记忆系统状态：");
                        ui.println(reactAgent.getMemoryManager().getSystemStatus());
                        ui.println("   当前项目作用域: " + reactAgent.getMemoryManager().getCurrentProject());
                        ui.println("   /memory list - 查看长期记忆");
                        ui.println("   /memory search <关键词> - 搜索当前项目可见长期记忆");
                        ui.println("   /memory delete <id> - 删除单条长期记忆");
                        ui.println("   /memory clear - 清空长期记忆");
                        ui.println("   /save <事实> - 保存项目级长期记忆；/save --global <事实> 保存全局记忆");
                        ui.println();
                        continue;
                    }
                    case MEMORY_LIST -> {   // /memory list 列出长期记忆
                        List<MemoryEntry> entries = reactAgent.getMemoryManager().listLongTerm();
                        ui.println(formatMemoryEntries("📋 长期记忆列表", entries));
                        ui.println();
                        continue;
                    }
                    case MEMORY_SEARCH -> {   // /memory search 搜索长期记忆
                        String query = command.payload();
                        if (query == null || query.isBlank()) {
                            ui.println("❌ 请提供搜索关键词，例如 /memory search Chrome 登录态\n");
                        } else {
                            List<MemoryEntry> entries = reactAgent.getMemoryManager().searchLongTerm(query, 20);   // 检索最多 20 条
                            ui.println(formatMemoryEntries("🔎 长期记忆搜索: " + query, entries));
                            ui.println();
                        }
                        continue;
                    }
                    case MEMORY_DELETE -> {   // /memory delete 删除单条长期记忆
                        String id = command.payload();
                        if (id == null || id.isBlank()) {
                            ui.println("❌ 请提供要删除的记忆 id，例如 /memory delete fact-abcd1234\n");
                        } else if (reactAgent.getMemoryManager().deleteLongTerm(id)) {
                            ui.println("🗑️ 已删除长期记忆: " + id + "\n");
                        } else {
                            ui.println("📭 未找到长期记忆: " + id + "\n");
                        }
                        continue;
                    }
                    case MEMORY_CLEAR -> {   // /memory clear 清空长期记忆
                        reactAgent.getMemoryManager().clearLongTerm();
                        ui.println("🧹 长期记忆已清空\n");
                        ui.println();
                        continue;
                    }
                    case MEMORY_SAVE -> {   // /save 保存长期记忆
                        MemorySaveRequest saveRequest = parseMemorySave(command.payload());   // 解析保存请求
                        if (saveRequest.fact().isEmpty()) {
                            ui.println("❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17，或 /save --global 默认用中文回答\n");
                        } else {
                            reactAgent.getMemoryManager().storeFact(saveRequest.fact(), saveRequest.scope());   // 存事实（project/global）
                            ui.println("💾 已保存到长期记忆(" + saveRequest.scope() + "): " + saveRequest.fact() + "\n");
                        }
                        continue;
                    }
                    case SWITCH_PLAN -> {   // /plan 进入 Plan 模式（或带参数直接执行）
                        if (command.payload() == null || command.payload().isEmpty()) {   // 无参数：只置标志
                            nextTaskUsePlanMode = true;
                            ui.println("📋 下一条任务将使用 Plan-and-Execute 模式，输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();   // 带参数：把 payload 当任务内容，落到下面直接走 plan
                    }
                    case SWITCH_TEAM -> {   // /team 进入 Multi-Agent 模式（或带参数直接执行）
                        if (command.payload() == null || command.payload().isEmpty()) {   // 无参数：只置标志
                            nextTaskUseTeamMode = true;
                            ui.println("👥 下一条任务将使用 Multi-Agent 协作模式（规划者 + 执行者 + 检查者），输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();   // 带参数：落到下面直接走 team
                    }
                    case SWITCH_MODEL -> {   // /model 切换模型
                        String selection = command.payload();
                        if (selection == null || selection.isEmpty()) {   // 无参数：显示当前模型和可用 provider
                            ui.println("🤖 当前模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                            ui.println("   GLM 明确模型：");
                            ui.println("   /model glm-5.1       - 切换到 GLM-5.1");
                            ui.println("   /model glm-5v-turbo  - 切换到 GLM-5V-Turbo 多模态");
                            ui.println("   其它 provider 使用你配置里的具体模型：");
                            ui.println("   /model deepseek      - 切换到 DeepSeek（读取配置模型）");
                            ui.println("   /model step          - 切换到 StepFun（读取配置模型）");
                            ui.println("   /model kimi          - 切换到 Kimi（读取配置模型）");
                            ui.println("   /model freellmapi    - 切换到本地 FreeLLMAPI（读取配置模型）");
                            ui.println("   /model xfyun         - 切换到讯飞星辰 MaaS（读取配置模型）\n");
                        } else {
                            ModelSelection target = resolveModelSelection(selection);   // 解析目标 provider/model
                            if (target.explicitModel()) {
                                ensureProviderConfig(config, target.provider()).setModel(target.model());   // 显式模型则写入配置
                            }
                            LlmClient newClient = LlmClientFactory.create(target.provider(), config);   // 按 provider 创建新客户端
                            if (newClient == null) {
                                ui.println("❌ 切换失败：未配置 " + target.provider() + " 的 API Key\n");
                            } else {
                                llmClient = newClient;   // 替换当前客户端
                                llmClientRef.set(newClient);   // 更新原子引用
                                config.setDefaultProvider(target.provider());   // 持久化默认 provider
                                config.save();
                                reactAgent.setLlmClient(llmClient);   // Agent 换用新客户端
                                ui.println("✅ 已切换到: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                                ui.println("   上下文策略: " + reactAgent.getMemoryManager().getContextProfile().summary());
                                ui.println("   对话上下文已保留，使用 /clear 可清空\n");
                                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                            }
                        }
                        continue;
                    }
                    case SWITCH_HITL -> {   // /hitl on/off 切换人工审批
                        String payload = command.payload();
                        if ("on".equals(payload)) {
                            hitlHandler.setEnabled(true);   // 开启 HITL
                            ui.println("🔒 HITL 审批已启用：write_file / execute_command / create_project 执行前将请求人工确认\n");
                        } else if ("off".equals(payload)) {
                            hitlHandler.setEnabled(false);   // 关闭 HITL
                            hitlHandler.clearApprovedAll();   // 清掉"全部放行"
                            ui.println("🔓 HITL 审批已关闭：危险操作将直接执行\n");
                        } else {   // 无参数：显示状态
                            String status = hitlHandler.isEnabled() ? "启用" : "关闭";
                            ui.println("🔒 HITL 当前状态：" + status);
                            ui.println("   /hitl on  - 启用人工审批");
                            ui.println("   /hitl off - 关闭人工审批\n");
                        }
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case POLICY_STATUS -> {   // /policy 显示安全策略状态
                        printPolicyStatus(ui, reactAgent);
                        continue;
                    }
                    case CONFIG -> {   // /config 配置管理
                        if (command.payload() == null || command.payload().isBlank()) {
                            handleConfigPalette(renderer, config, llmClient, hitlHandler, skillRegistry);   // 无参数走配置面板
                        } else {
                            ui.println(handleConfigCommand(config, command.payload()));   // 带参数执行配置命令
                            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        }
                        continue;
                    }
                    case AUDIT_TAIL -> {   // /audit 查看审计记录
                        printAuditTail(ui, reactAgent, command.payload());
                        continue;
                    }
                    case SNAPSHOT -> {   // /snapshot 查看快照
                        printSnapshotCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                        continue;
                    }
                    case RESTORE_SNAPSHOT -> {   // /restore 恢复快照
                        printRestoreCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                        continue;
                    }
                    case MCP_LIST -> {   // /mcp 查看所有 MCP server 状态
                        ui.println(mcpServerManager.formatStatus());
                        ui.println();
                        continue;
                    }
                    case MCP_RESTART -> {   // /mcp restart <name> 重启单个 server
                        printMcpCommandResult(ui, mcpServerManager.restart(command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case MCP_LOGS -> {   // /mcp logs <name> 查看 server 日志
                        printMcpCommandResult(ui, mcpServerManager.logs(command.payload()));
                        continue;
                    }
                    case MCP_DISABLE -> {   // /mcp disable <name> 运行时禁用 server
                        printMcpCommandResult(ui, mcpServerManager.disable(command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case MCP_ENABLE -> {   // /mcp enable <name> 运行时启用 server
                        printMcpCommandResult(ui, mcpServerManager.enable(command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case MCP_RESOURCES -> {   // /mcp resources <name> 查看 server 暴露的 resources
                        printMcpCommandResult(ui, mcpServerManager.resources(command.payload()));
                        continue;
                    }
                    case MCP_PROMPTS -> {   // /mcp prompts <name> 查看 server 暴露的 prompts
                        printMcpCommandResult(ui, mcpServerManager.prompts(command.payload()));
                        continue;
                    }
                    case BROWSER -> {   // /browser 浏览器会话管理（status/connect/disconnect/tabs）
                        printMcpCommandResult(ui, handleBrowserCommand(
                                command.payload(),
                                browserSession,
                                browserConnectivityCheck,
                                mcpServerManager,
                                hitlToolRegistry,
                                hitlHandler));
                        continue;
                    }
                    case WECHAT -> {   // /wechat 微信 iLink 通道
                        ui.println(handleWechatCommand(command.payload(), lineReader, renderer, ui, wechatRuntime));
                        continue;
                    }
                    case TASK -> {   // /task 后台任务管理
                        printMcpCommandResult(ui, TaskCommandFormatter.handle(taskManager, command.payload()));
                        continue;
                    }
                    case SKILL_LIST -> {   // /skill list 列出所有 skill
                        ui.println(SkillCommandHandler.list(skillRegistry));
                        continue;
                    }
                    case SKILL_SHOW -> {   // /skill show <name> 查看 skill 详情
                        ui.println(SkillCommandHandler.show(skillRegistry, command.payload()));
                        continue;
                    }
                    case SKILL_ON -> {   // /skill on <name> 启用 skill
                        ui.println(SkillCommandHandler.enable(skillRegistry, skillStateStore, command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case SKILL_OFF -> {   // /skill off <name> 禁用 skill
                        ui.println(SkillCommandHandler.disable(skillRegistry, skillStateStore, command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case SKILL_RELOAD -> {   // /skill reload 重新扫描 skill 目录
                        skillRegistry.reload();
                        ui.println("🔄 已重新扫描 skill 目录");
                        ui.println(SkillCommandHandler.startupSummary(skillRegistry));
                        ui.println("✅ 下一轮 LLM 调用生效");
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case EXPORT -> {   // /export 导出会话为 Markdown
                        handleExportCommand(ui, reactAgent);
                        continue;
                    }
                    case INDEX_CODE -> {   // /index 索引代码库（分块 + embedding + 存 SQLite）
                        String indexPath = command.payload() != null ? command.payload() : ".";   // 默认索引当前目录
                        CodeIndex indexer = new CodeIndex(ui::println);   // 创建索引器（进度走 ui 输出）
                        indexer.index(indexPath);   // 执行索引
                        ui.println();

                        // 同步项目路径到 ToolRegistry，让 search_code 工具可以正常工作
                        String absPath = new File(indexPath).getAbsolutePath();
                        reactAgent.getToolRegistry().setProjectPath(absPath);   // 同步项目根
                        reactAgent.getMemoryManager().setProjectPath(absPath);   // 同步记忆作用域
                        continue;
                    }
                    case SEARCH_CODE -> {   // /search 语义检索代码库
                        String query = command.payload();
                        if (query == null || query.isEmpty()) {
                            ui.println("❌ 请提供检索关键词，例如 /search 用户登录实现\n");
                            continue;
                        }
                        ui.println("🔍 检索: " + query);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {   // 打开检索器
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {   // 未索引
                                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<com.paicli.rag.VectorStore.SearchResult> results = retriever.hybridSearch(query, 5);   // 混合检索 Top5
                            if (results.isEmpty()) {
                                ui.println("📭 未找到相关代码\n");
                            } else {
                                ui.println(SearchResultFormatter.formatForCli(query, results) + "\n");
                            }
                        } catch (Exception e) {
                            ui.println("❌ 检索失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case GRAPH_QUERY -> {   // /graph 查询代码关系图谱
                        String className = command.payload();
                        if (className == null || className.isEmpty()) {
                            ui.println("❌ 请提供类名，例如 /graph Main\n");
                            continue;
                        }
                        ui.println("🕸️ 查询类关系图谱: " + className);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {   // 未索引
                                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<CodeRelation> relations = retriever.getRelationGraph(className);   // 查关系
                            if (relations.isEmpty()) {
                                ui.println("📭 未找到相关关系\n");
                            } else {
                                ui.println("📋 找到 " + relations.size() + " 条关系:\n");
                                for (CodeRelation rel : relations) {
                                    String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                                            : rel.relationType().equals("extends") ? "└── extends -->"
                                            : rel.relationType().equals("implements") ? "└── implements -->"
                                            : rel.relationType().equals("calls") ? "├── calls -->"
                                            : "├── " + rel.relationType() + " -->";   // 按关系类型选箭头符号
                                    ui.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                                            rel.toName() != null ? rel.toName() : "unknown");
                                }
                                ui.println();
                            }
                        } catch (Exception e) {
                            ui.println("❌ 查询失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case NONE -> {   // 普通输入（非斜杠命令），落到下面走 Agent
                    }
                }

                // ============ 运行 Agent ============
                String submittedInput = input;   // 保存原始输入（回显用）
                input = mentionExpander.expand(input);   // 展开 MCP @server:uri 引用
                input = localPathMentionExpander.expand(input);   // 展开本地 @path 引用
                if (!(renderer instanceof InlineRenderer)) {
                    ui.println();   // 非 inline 补空行
                }
                if (!submittedInputRendered) {   // 普通输入还没回显
                    renderer.beginTurn();   // 开始一轮
                    printSubmittedInput(renderer, ui, submittedInput);   // 回显输入
                }
                final String taskInput = input;   // 最终任务文本
                Callable<String> runTask;   // 任务执行器（三选一）
                String snapshotMode;   // 快照模式标签
                if (nextTaskUsePlanMode || command.type() == CliCommandParser.CommandType.SWITCH_PLAN) {   // 走 Plan 路径 P562 nextTaskUsePlanMode = true;导致其可以进入plan分路
                    snapshotMode = "plan";
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        PlanExecuteAgent planAgent = createPlanAgent(activeClient, reactAgent, terminal, lineReader, ui);   // 新建 Plan agent,共用react模式下的工具列表和记忆管理
                        planAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);   // 注入 MCP resource 索引
                        planAgent.setSkillRegistry(skillRegistry);   // 注入 skill
                        planAgent.setSkillContextBuffer(skillContextBuffer);   // 注入 skill buffer
                        return planAgent.run(taskInput);   // 执行 Plan 路径
                    };
                } else if (nextTaskUseTeamMode || command.type() == CliCommandParser.CommandType.SWITCH_TEAM) {   // 走 Team 路径
                    snapshotMode = "team";
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent, ui);   // 新建编排器
                        orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);   // 注入 MCP resource 索引
                        orchestrator.setSkillSystem(skillRegistry, skillContextBuffer);   // 注入 skill 系统
                        return orchestrator.run(taskInput);   // 执行 Team 路径
                    };
                } else {   // 默认走 ReAct
                    snapshotMode = "react";
                    runTask = () -> reactAgent.run(taskInput);   // 执行 ReAct 路径
                }
                SnapshotService snapshotService = reactAgent.getToolRegistry().getSnapshotService();   // 拿快照服务
                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, snapshotMode));   // 状态栏显示当前模式
                String response = runWithCancelSupport(terminal,   // 带取消支持地运行（ESC/Ctrl+C）
                        ui,
                        () -> snapshotService.runTurn(snapshotMode, taskInput, runTask::call));   // 快照包裹（pre/post-turn）+ 执行任务,执行/plan模式命令
                if (!"react".equals(snapshotMode)) {   // plan/team 结束
                    renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));   // 状态回 idle
                }
                nextTaskUsePlanMode = false;   // 复位 plan 标志
                nextTaskUseTeamMode = false;   // 复位 team 标志
                if (response != null && !response.isBlank()) {   // 有结果
                    ui.println(response);   // 输出结果
                    ui.println();
                }
            }

            // ============ 阶段 13：收尾 ============
            ui.println("\n👋 再见!");   // 退出循环后打印再见
            wechatRuntime.stop();   // 停微信通道
            renderer.close();   // 关闭渲染器

        } catch (IOException e) {   // 终端初始化失败
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);   // 退出
        }
    }   // main 方法结束

    private static boolean isRuntimeServeCommand(String[] args) {
        return args != null
                && args.length >= 1
                && "serve".equalsIgnoreCase(args[0])
                && java.util.Arrays.stream(args).anyMatch("--http"::equalsIgnoreCase);
    }

    private static void startRuntimeApiAndBlock(String[] args) {
        PaiCliConfig config = PaiCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.exit(1);
        }
        int port = parseServePort(args, 8080);
        try {
            RuntimeThreadStore store = new RuntimeThreadStore(RuntimeThreadStore.defaultDbPath());
            RuntimeApiServer server = new RuntimeApiServer(
                    store,
                    prompt -> runHeadlessTask(prompt, client),
                    port,
                    RuntimeApiServer.configuredApiKey());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                store.close();
            }, "paicli-runtime-api-shutdown"));
            server.start();
            System.out.println("✅ PaiCLI Runtime API 已启动: http://127.0.0.1:" + server.port());
            System.out.println("   认证: Authorization: Bearer <PAICLI_RUNTIME_API_KEY>");
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("❌ Runtime API 启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int parseServePort(String[] args, int defaultPort) {
        if (args == null) {
            return defaultPort;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equalsIgnoreCase(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    return defaultPort;
                }
            }
        }
        return defaultPort;
    }

    private static String runHeadlessTask(String prompt, LlmClient llmClient) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        Agent agent = new Agent(llmClient, registry);
        return agent.run(prompt);
    }

    private static DurableTaskManager openTaskManager(AtomicReference<LlmClient> llmClientRef) {
        try {
            return DurableTaskManager.openDefault(prompt -> runHeadlessTask(prompt, llmClientRef.get()));
        } catch (Exception e) {
            throw new IllegalStateException("后台任务管理器初始化失败: " + e.getMessage(), e);
        }
    }

    private static String handleWechatCommand(String payload,
                                              LineReader lineReader,
                                              Renderer renderer,
                                              PrintStream out,
                                              WechatRuntimeController runtime) {
        String action = payload == null || payload.isBlank() ? "start" : payload.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "start", "on" -> {
                    WechatAccount account = WechatAccountStore.createDefault()
                            .loadLatest()
                            .orElseGet(() -> setupWechatAccount(lineReader, renderer, out));
                    yield runtime.start(account);
                }
                case "setup", "bind" -> {
                    WechatAccount account = setupWechatAccount(lineReader, renderer, out);
                    yield runtime.start(account);
                }
                case "status" -> runtime.status();
                case "stop", "off" -> {
                    runtime.stop();
                    yield "微信通道已停止。";
                }
                case "restart" -> {
                    runtime.stop();
                    WechatAccount account = WechatAccountStore.createDefault()
                            .loadLatest()
                            .orElseGet(() -> setupWechatAccount(lineReader, renderer, out));
                    yield runtime.start(account);
                }
                default -> """
                        未知 /wechat 子命令: %s
                        用法:
                          /wechat          绑定并启动；已绑定时直接启动
                          /wechat setup    重新扫码绑定并启动
                          /wechat status   查看当前进程内微信通道状态
                          /wechat stop     停止当前进程内微信通道
                        """.formatted(action).trim();
            };
        } catch (UserInterruptException e) {
            return "已取消微信通道操作。";
        } catch (Exception e) {
            return "微信通道操作失败: " + e.getMessage();
        }
    }

    private static WechatAccount setupWechatAccount(LineReader lineReader, Renderer renderer, PrintStream out) {
        try {
            IlinkClient client = new IlinkClient();
            WechatAccountStore store = WechatAccountStore.createDefault();
            Path defaultWorkspace = Path.of(".").toAbsolutePath().normalize();
            String workspace;
            renderer.beforeInput();
            try {
                workspace = lineReader.readLine("请输入微信通道工作区 [" + defaultWorkspace + "]: ");
            } finally {
                renderer.afterInput();
            }
            if (workspace == null || workspace.isBlank()) {
                workspace = defaultWorkspace.toString();
            }

            WechatQrLogin qr = client.startQrLogin("3");
            out.println("请用目标微信扫描二维码：");
            com.paicli.wechat.TerminalQrRenderer.print(out, qr.qrcodeUrl());
            out.println("扫码失败时可打开链接：" + qr.qrcodeUrl());
            out.println("等待扫码确认...");

            WechatLoginResult login = waitWechatLogin(client, qr.qrcodeId(), Duration.ofMinutes(5));
            if (!login.connected()) {
                throw new IllegalStateException("扫码绑定未完成: " + login.message());
            }
            WechatAccount account = store.createAccount(
                    login.token(),
                    login.accountId(),
                    login.baseUrl(),
                    login.userId(),
                    workspace);
            store.save(account);
            out.println("微信通道绑定完成");
            out.println("账号: " + login.accountId());
            out.println("工作区: " + workspace);
            return account;
        } catch (UserInterruptException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static WechatLoginResult waitWechatLogin(IlinkClient client, String qrcodeId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            WechatLoginResult result = client.pollQrStatus(qrcodeId);
            if (result.connected() || result.expired()) {
                return result;
            }
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("等待扫码超时");
    }

    private static final class WechatRuntimeController {
        private final Renderer renderer;
        private WechatMessageLoop loop;
        private Thread thread;
        private WechatAccount account;

        private WechatRuntimeController(Renderer renderer) {
            this.renderer = renderer;
        }

        synchronized String start(WechatAccount account) {
            if (isRunning()) {
                return "微信通道已在运行，账号: " + this.account.accountId();
            }
            this.account = account;
            this.loop = new WechatMessageLoop(new IlinkClient(), WechatAccountStore.createDefault(), account, renderer);
            this.thread = new Thread(() -> {
                try {
                    loop.run();
                } catch (Exception e) {
                    System.err.println("微信通道已退出: " + e.getMessage());
                }
            }, "paicli-wechat-channel");
            this.thread.setDaemon(true);
            this.thread.start();
            return "微信通道已启动，账号: " + account.accountId();
        }

        synchronized void stop() {
            if (loop != null) {
                loop.stop();
            }
            if (thread != null) {
                thread.interrupt();
            }
            loop = null;
            thread = null;
        }

        synchronized String status() {
            if (isRunning()) {
                return "微信通道运行中，账号: " + account.accountId()
                        + "\n工作区: " + account.workspace();
            }
            return "微信通道未运行。输入 /wechat 启动。";
        }

        private boolean isRunning() {
            return thread != null && thread.isAlive();
        }
    }

    static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                            PlanExecuteAgent.PlanReviewHandler reviewHandler) {
        return new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                reviewHandler,
                System.out
        );
    }

    private static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                                    Terminal terminal, LineReader lineReader, PrintStream out) {
        out.println("📋 使用 Plan-and-Execute 模式\n");
        return new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                createPlanReviewHandler(terminal, lineReader, out),
                out
        );
    }

    private static AgentOrchestrator createTeamAgent(LlmClient llmClient, Agent reactAgent, PrintStream out) {
        out.println("👥 使用 Multi-Agent 协作模式\n");
        return new AgentOrchestrator(llmClient, reactAgent.getToolRegistry(), reactAgent.getMemoryManager(), out);
    }

    private static String runWithCancelSupport(Terminal terminal, PrintStream out, Callable<String> task) {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-agent-runner");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> future = executor.submit(task);
        // 进入 raw mode 监听 ESC：raw mode 关 ICANON / ECHO / IEXTEN 但保留 ISIG，所以 Ctrl+C 仍能终止 PaiCLI。
        Attributes original = null;
        try {
            if (terminal != null) {
                try {
                    original = terminal.enterRawMode();
                } catch (Exception ignored) {
                    // raw mode 进入失败（非交互终端等），降级为不监听 ESC，靠 Ctrl+C 退出。
                }
            }
            while (!future.isDone()) {
                if (original != null && readEscCancel(terminal)) {
                    token.cancel();
                    future.cancel(true);
                    executor.shutdownNow();
                    return "⏹️ 已请求取消当前任务。";
                }
                try {
                    return future.get(150, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // 继续监听 ESC
                }
            }
            return future.get();
        } catch (CancellationException e) {
            return "⏹️ 已取消当前任务。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            token.cancel();
            future.cancel(true);
            return "⏹️ 已取消当前任务。";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
            return "❌ 执行失败: " + message;
        } finally {
            if (terminal != null && original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (Exception ignored) {
                }
            }
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }

    /**
     * 任务运行期间监听 ESC 按键。raw mode 下 ESC 字节是 0x1b（27）。
     *
     * 关键陷阱：方向键 / Home / End 等由 ESC + 控制序列组成（如 ESC[A），不能误判为单 ESC 取消。
     * 复用 {@link #readInputBurst} + {@link #classifyEscapeSequence}：
     * - STANDALONE_ESC（孤立的 ESC）→ 用户取消
     * - CONTROL_SEQUENCE / BRACKETED_PASTE / OTHER → 丢弃，不取消
     */
    static boolean readEscCancel(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        try {
            NonBlockingReader reader = terminal.reader();
            int next = reader.read(50);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return false;
            }
            String escTail = next == 27 ? readInputBurst(terminal, 80, 20, 120) : null;
            if (next != 27) {
                // 非 ESC 输入，drain 这一轮残余字节避免堆积，但不触发取消。
                while (true) {
                    int more = reader.read(1);
                    if (more == NonBlockingReader.READ_EXPIRED || more < 0) {
                        break;
                    }
                }
            }
            return decideEscCancel(next, escTail);
        } catch (Exception ignored) {
            // 监听是 best-effort；失败不能影响任务执行。
            return false;
        }
    }

    /**
     * ESC 取消判断的纯函数版（不依赖终端 IO，便于单测）。
     *
     * @param firstByte ESC=27 触发判断；其他字节直接返回 false
     * @param escTail  紧跟 ESC 之后的字节序列（不含 ESC 本身）；null / 空 → 单 ESC 取消
     */
    static boolean decideEscCancel(int firstByte, String escTail) {
        if (firstByte != 27) {
            return false;
        }
        return classifyEscapeSequence(escTail) == EscapeSequenceType.STANDALONE_ESC;
    }

    private static PromptInput readPromptInput(Terminal terminal,
                                               LineReader lineReader,
                                               Renderer renderer,
                                               boolean allowEscCancel,
                                               boolean spaciousPrompt)
            throws UserInterruptException, EndOfFileException {
        if (spaciousPrompt) {
            renderer.stream().println();
        }
        renderer.beforeInput();
        try {
            String prompt = renderer.inputPrompt();
            String rightPrompt = renderer.inputRightPrompt();
            if (!allowEscCancel) {
                return PromptInput.submitted(lineReader.readLine(prompt, rightPrompt, (MaskingCallback) null, null));
            }

            if (terminal != null && terminal.writer() != null) {
                terminal.writer().print(prompt);
                terminal.writer().flush();
            } else {
                renderer.stream().print(prompt);
                renderer.stream().flush();
            }

            PrefillResult prefill = readPrefillInputFromTerminal(terminal, lineReader);
            if (prefill == null) {
                return PromptInput.submitted(lineReader.readLine("", rightPrompt, (MaskingCallback) null, null));
            }

            if (prefill.canceled()) {
                return PromptInput.canceledInput();
            }

            if (prefill.submitted()) {
                return PromptInput.submitted("");
            }

            return PromptInput.submitted(lineReader.readLine("", rightPrompt, (MaskingCallback) null, prefill.seedBuffer()));
        } finally {
            renderer.afterInput();
        }
    }

    static boolean defaultSpaciousPrompt(boolean statusBarAvailable) {
        return false;
    }

    static void printSubmittedPrompt(PrintStream out, String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        out.println(AnsiStyle.userMessageBlock(visible, terminalColumns()));
    }

    static void printSubmittedInput(Renderer renderer, PrintStream out, String input) {
        String visible = redactSensitiveInput(input);
        if (renderer instanceof InlineRenderer inline) {
            inline.printSubmittedPrompt(visible);
        } else {
            printSubmittedPrompt(out, visible);
        }
    }

    static String redactSensitiveInput(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String redacted = SENSITIVE_FLAG_VALUE.matcher(input).replaceAll("$1***");
        return SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1***");
    }

    private static int terminalColumns() {
        String configured = System.getProperty("paicli.render.columns");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String columns = System.getenv("COLUMNS");
        if (columns != null && !columns.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(columns.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 120;
    }

    private static void refreshTerminalColumns(Terminal terminal) {
        if (terminal == null || terminal.getSize() == null || terminal.getSize().getColumns() <= 0) {
            return;
        }
        System.setProperty("paicli.render.columns", String.valueOf(Math.max(40, terminal.getSize().getColumns())));
    }

    static void configureAwtForCli() {
        if (!isMacOs()) {
            return;
        }
        System.setProperty("java.awt.headless", "true");
    }

    static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
    // 人工审阅计划的交互实现,计划构建好之后先打印在终端,然后询问用户是否可以,用户可以同意(按当前计划执行)/取消本次计划/输入补充要求后重新规划
    private static PlanExecuteAgent.PlanReviewHandler createPlanReviewHandler(Terminal terminal,
                                                                              LineReader lineReader,
                                                                              PrintStream out) {
        return (String goal, ExecutionPlan plan) -> { // 绑定PlanExecuteAgent
            boolean expanded = false;
            out.println(plan.summarize());
            out.println("📝 计划已生成。");
            out.println("   - 回车：按当前计划执行");
            out.println("   - Ctrl+O：展开完整计划");
            out.println("   - ESC：折叠或取消本次计划");
            out.println("   - I：输入补充要求后重新规划\n");

            while (true) {
                KeyReadResult keyReadResult = readSingleKeyFromTerminal(terminal);
                if (keyReadResult.ignoredControlSequence()) {
                    continue;
                }

                Integer key = keyReadResult.key();
                if (key != null) {
                    // Enter
                    if (key == '\n' || key == '\r') {
                        out.println();
                        return PlanExecuteAgent.PlanReviewDecision.execute();
                    }

                    // ESC (27)
                    if (key == 27) {
                        out.println();
                        if (expanded) {
                            expanded = false;
                            out.println(plan.summarize());
                            out.println("📁 已退出完整计划视图，继续按 Enter / Ctrl+O / ESC / I。\n");
                            continue;
                        }
                        return PlanExecuteAgent.PlanReviewDecision.cancel();
                    }

                    // I 或 i
                    if (key == 'i' || key == 'I') {
                        out.println();
                        String supplementInput = lineReader.readLine("补充> ").trim();
                        PlanReviewInputParser.Decision supplementDecision =
                                PlanReviewInputParser.parse(supplementInput);
                        return mapReviewDecision(supplementDecision);
                    }

                    // Ctrl+O
                    if (key == CTRL_O) {
                        out.println();
                        out.println(plan.visualize());
                        expanded = true;
                        out.println("👆 已展开完整计划，继续按 Enter / Ctrl+O / ESC / I。\n");
                        continue;
                    }

                    out.println();
                    out.println("未识别按键，请按 Enter / Ctrl+O / ESC / I。\n");
                    continue;
                }

                // 如果无法读取单键，回退到行输入模式
                String decisionInput = lineReader.readLine("操作/补充> ").trim();
                if (decisionInput.equalsIgnoreCase("/view")) {
                    out.println();
                    out.println(plan.visualize());
                    expanded = true;
                    out.println("👆 已展开完整计划，继续输入 Enter / /cancel / 补充要求。\n");
                    continue;
                }
                PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(decisionInput);
                return mapReviewDecision(decision);
            }
        };
    }

    private static KeyReadResult readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return KeyReadResult.unavailable();
                }

                if (key == 27) {
                    String escapeSequence = readInputBurst(terminal, 80, 20, 120);
                    EscapeSequenceType escapeSequenceType = classifyEscapeSequence(escapeSequence);
                    if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
                        return KeyReadResult.keyPressed(27);
                    }
                    if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE
                            || escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
                        return KeyReadResult.ignoredSequence();
                    }
                }

                return KeyReadResult.keyPressed(key);
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return KeyReadResult.unavailable();
        }
    }

    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal, LineReader lineReader) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                if (key == 27) {
                    return readEscapeInput(terminal, lineReader);
                }

                if (isSubmitKey(key)) {
                    return PrefillResult.submittedInput();
                }

                String rawInput = switch (key) {
                    case 8, 127 -> "";
                    default -> Character.toString((char) key);
                };

                rawInput += readInputBurst(terminal, 20, 25, 250);
                return PrefillResult.seed(prepareSeedBuffer(rawInput));
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static PrefillResult readEscapeInput(Terminal terminal, LineReader lineReader)
            throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 80, 20, 300);
        EscapeSequenceType escapeSequenceType = classifyEscapeSequence(sequence);
        if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
            return PrefillResult.canceledInput();
        }

        if (escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
            String pastedText = sequence.substring(BRACKETED_PASTE_BEGIN.length());
            while (!pastedText.contains(BRACKETED_PASTE_END)) {
                String burst = readInputBurst(terminal, 30, 25, 500);
                if (burst.isEmpty()) {
                    break;
                }
                pastedText += burst;
            }

            return PrefillResult.seed(prepareSeedBuffer(stripBracketedPasteEndMarker(pastedText)));
        }

        if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE) {
            return PrefillResult.seed(seedBufferForHistoryNavigation(lineReader, sequence));
        }

        return PrefillResult.canceledInput();
    }

    private static String readInputBurst(Terminal terminal, long firstWaitMs, long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        NonBlockingReader reader = terminal.reader();
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long waitMs = firstWaitMs;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            int next = reader.read(waitMs);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }
            buffer.append((char) next);
            waitMs = idleWaitMs;
        }

        return buffer.toString();
    }

    static String prepareSeedBuffer(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return "";
        }
        return normalizeLineEndings(rawInput);
    }

    static List<String> startupHints() {
        return List.of(
                "输入你的问题或任务",
                "输入 '/' 后按 Tab 补全命令",
                "输入 '@server:protocol://path' 可显式引用 MCP resource",
                "任务运行中按 ESC 取消当前任务",
                "默认模式是 ReAct"
        );
    }

    record SlashCommandHint(String insertText, String display, String description) {
    }

    static List<SlashCommandHint> slashCommandHints() {
        return List.of(
                new SlashCommandHint("/model", "/model", "查看当前模型"),
                new SlashCommandHint("/model glm-5.1", "/model glm-5.1", "切换到 GLM-5.1"),
                new SlashCommandHint("/model glm-5v-turbo", "/model glm-5v-turbo", "切换到 GLM-5V-Turbo 多模态"),
                new SlashCommandHint("/model deepseek", "/model deepseek", "切换到 DeepSeek（读取配置模型）"),
                new SlashCommandHint("/model step", "/model step", "切换到 StepFun（读取配置模型）"),
                new SlashCommandHint("/model kimi", "/model kimi", "切换到 Kimi（读取配置模型）"),
                new SlashCommandHint("/model freellmapi", "/model freellmapi", "切换到本地 FreeLLMAPI（读取配置模型）"),
                new SlashCommandHint("/model xfyun", "/model xfyun", "切换到讯飞星辰 MaaS（读取配置模型）"),
                new SlashCommandHint("/config provider freellmapi ", "/config provider freellmapi <选项>", "配置本地 FreeLLMAPI provider"),
                new SlashCommandHint("/config provider xfyun ", "/config provider xfyun <选项>", "配置讯飞星辰 MaaS provider"),
                new SlashCommandHint("/plan", "/plan", "下一条任务使用 Plan-and-Execute 模式"),
                new SlashCommandHint("/plan ", "/plan <任务内容>", "直接用计划模式执行这条任务"),
                new SlashCommandHint("/team", "/team", "下一条任务使用 Multi-Agent 协作模式"),
                new SlashCommandHint("/team ", "/team <任务内容>", "直接用多 Agent 协作执行这条任务"),
                new SlashCommandHint("/hitl", "/hitl", "查看 HITL 状态"),
                new SlashCommandHint("/hitl on", "/hitl on", "启用危险操作人工审批"),
                new SlashCommandHint("/hitl off", "/hitl off", "关闭 HITL 审批"),
                new SlashCommandHint("/browser", "/browser", "查看浏览器会话状态"),
                new SlashCommandHint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new SlashCommandHint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new SlashCommandHint("/browser status", "/browser status", "查看浏览器会话状态"),
                new SlashCommandHint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new SlashCommandHint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new SlashCommandHint("/wechat", "/wechat", "扫码绑定并启动微信 iLink 通道"),
                new SlashCommandHint("/wechat setup", "/wechat setup", "重新扫码绑定并启动微信通道"),
                new SlashCommandHint("/wechat status", "/wechat status", "查看微信通道状态"),
                new SlashCommandHint("/wechat stop", "/wechat stop", "停止当前进程内微信通道"),
                new SlashCommandHint("/task", "/task", "查看后台任务列表"),
                new SlashCommandHint("/task add ", "/task add <任务内容>", "提交后台任务"),
                new SlashCommandHint("/task cancel ", "/task cancel <task_id>", "取消后台任务"),
                new SlashCommandHint("/task log ", "/task log <task_id>", "查看后台任务结果"),
                new SlashCommandHint("/mcp", "/mcp", "查看 MCP server 状态"),
                new SlashCommandHint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new SlashCommandHint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new SlashCommandHint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new SlashCommandHint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new SlashCommandHint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new SlashCommandHint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new SlashCommandHint("/policy", "/policy", "查看安全策略状态"),
                new SlashCommandHint("/config", "/config", "打开配置 palette（只读视图 + 切换提示）"),
                new SlashCommandHint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new SlashCommandHint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new SlashCommandHint("/snapshot", "/snapshot", "查看最近 Side-Git 快照"),
                new SlashCommandHint("/snapshot status", "/snapshot status", "查看 Side-Git 快照状态"),
                new SlashCommandHint("/snapshot clean", "/snapshot clean", "清理当前项目 Side-Git 快照"),
                new SlashCommandHint("/restore ", "/restore <N>", "恢复到最近第 N 个 pre-turn 快照"),
                new SlashCommandHint("/index", "/index", "索引当前代码库"),
                new SlashCommandHint("/index ", "/index [路径]", "索引指定路径代码库"),
                new SlashCommandHint("/search ", "/search <查询>", "语义检索代码（RAG 辅助）"),
                new SlashCommandHint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new SlashCommandHint("/clear", "/clear", "清空当前对话历史"),
                new SlashCommandHint("/compact", "/compact", "手动压缩当前对话历史"),
                new SlashCommandHint("/init", "/init", "生成项目级记忆 PAI.md"),
                new SlashCommandHint("/init --force", "/init --force", "重写项目级记忆 PAI.md"),
                new SlashCommandHint("/history clear", "/history clear", "清空本机输入历史"),
                new SlashCommandHint("/context", "/context", "查看上下文和记忆状态"),
                new SlashCommandHint("/memory", "/memory", "查看记忆状态"),
                new SlashCommandHint("/memory list", "/memory list", "查看长期记忆列表"),
                new SlashCommandHint("/memory search ", "/memory search <关键词>", "搜索当前项目可见长期记忆"),
                new SlashCommandHint("/memory delete ", "/memory delete <id>", "删除单条长期记忆"),
                new SlashCommandHint("/memory clear", "/memory clear", "清空长期记忆"),
                new SlashCommandHint("/save ", "/save [--global] <事实内容>", "手动保存项目级或全局长期记忆"),
                new SlashCommandHint("/skill", "/skill", "查看 skill 列表"),
                new SlashCommandHint("/skill list", "/skill list", "查看 skill 列表"),
                new SlashCommandHint("/skill show ", "/skill show <name>", "查看 SKILL.md 全文"),
                new SlashCommandHint("/skill on ", "/skill on <name>", "启用 skill"),
                new SlashCommandHint("/skill off ", "/skill off <name>", "禁用 skill"),
                new SlashCommandHint("/skill reload", "/skill reload", "重新扫描 skill 目录"),
                new SlashCommandHint("/export", "/export", "导出当前会话对话记录为 Markdown"),
                new SlashCommandHint("/exit", "/exit", "退出 PaiCLI"),
                new SlashCommandHint("/quit", "/quit", "退出 PaiCLI")
        );
    }

    private static void printSlashCommandHelp() {
        printSlashCommandHelp(System.out);
    }

    private static void printSlashCommandHelp(PrintStream out) {
        out.println("可用命令：");
        for (SlashCommandHint hint : slashCommandHints()) {
            out.println("   " + hint.display() + " - " + hint.description());
        }
        out.println();
    }

    static void configureSlashCommandHint(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-slash-command-hint", () -> {
            lineReader.getBuffer().write("/");
            return true;
        });
        Reference slashHint = new Reference("paicli-slash-command-hint");
        bindSlashWidget(lineReader, LineReader.MAIN, slashHint);
        bindSlashWidget(lineReader, LineReader.EMACS, slashHint);
        bindSlashWidget(lineReader, LineReader.VIINS, slashHint);
    }

    static void configureJLineInteractiveWidgets(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        new AutosuggestionWidgets(lineReader).enable();
        new AutopairWidgets(lineReader).enable();
        // JLine TailTipWidgets 会通过 Status 预留多行底部区域；如果在首屏前 enable，
        // banner 前会出现大段空白，输入行下方也会长期空出一块。命令说明后续用
        // 不预留布局的方式展示，避免破坏 Claude Code / Qoder 风格的 inline 体验。
    }

    static LinkedHashMap<String, CmdDesc> slashCommandTailTips() {
        LinkedHashMap<String, CmdDesc> tips = new LinkedHashMap<>();
        for (SlashCommandHint hint : slashCommandHints()) {
            tips.computeIfAbsent(hint.insertText(), key ->
                    new CmdDesc().mainDesc(List.of(new AttributedString(hint.description()))));
            tips.computeIfAbsent(hint.display(), key ->
                    new CmdDesc().mainDesc(List.of(new AttributedString(hint.description()))));
        }
        return tips;
    }

    private static void bindSlashWidget(LineReader lineReader, String keyMapName, Reference slashHint) {
        KeyMap<org.jline.reader.Binding> keyMap = lineReader.getKeyMaps().get(keyMapName);
        if (keyMap != null) {
            keyMap.bind(slashHint, "/");
        }
    }

    static String formatSlashCommandChoices(int terminalWidth) {
        List<String> commands = slashCommandHints().stream()
                .map(SlashCommandHint::display)
                .distinct()
                .toList();
        int maxLen = commands.stream().mapToInt(String::length).max().orElse(12);
        int colWidth = Math.min(Math.max(maxLen + 4, 18), Math.max(18, terminalWidth));
        int columns = Math.max(1, Math.min(4, terminalWidth / colWidth));
        int rows = (int) Math.ceil(commands.size() / (double) columns);

        StringBuilder sb = new StringBuilder();
        sb.append("可用命令（Tab 补全，Enter 执行）：\n");
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int index = col * rows + row;
                if (index >= commands.size()) {
                    continue;
                }
                String command = commands.get(index);
                sb.append(command);
                if (col < columns - 1) {
                    sb.append(" ".repeat(Math.max(2, colWidth - command.length())));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * /config 命令处理：用 renderer.openPalette 展示当前配置项列表。
     * 当前是只读视图——选中一项后提示对应的 CLI 命令，由用户自己执行。
     */
    private static void handleConfigPalette(Renderer renderer,
                                            PaiCliConfig config,
                                            LlmClient llmClient,
                                            SwitchableHitlHandler hitlHandler,
                                            com.paicli.skill.SkillRegistry skillRegistry) {
        var items = java.util.List.of(
                "模型: " + (llmClient == null ? "(none)" : llmClient.getModelName() + " / " + llmClient.getProviderName()),
                "默认 Provider: " + (config == null ? "(none)" : config.getDefaultProvider()),
                "HITL: " + (hitlHandler.isEnabled() ? "ON" : "OFF"),
                "Skill 启用数: " + (skillRegistry == null ? 0 : skillRegistry.enabledSkills().size()),
                "渲染器: " + renderer.getClass().getSimpleName(),
                "配置文件: ~/.paicli/config.json (只读视图，编辑请用编辑器)"
        );
        int selected = renderer.openPalette("配置 / config", items);
        if (selected < 0) {
            renderer.stream().println("(已关闭)");
            return;
        }
        String hint = switch (selected) {
            case 0, 1 -> "💡 GLM: /model glm-5.1 / /model glm-5v-turbo；其它: /model deepseek|step|kimi|freellmapi|xfyun 读取配置模型";
            case 2 -> "💡 切换 HITL: /hitl on / /hitl off";
            case 3 -> "💡 管理 Skill: /skill list / /skill on <name> / /skill off <name>";
            case 4 -> "💡 切换渲染器（重启后生效）: PAICLI_RENDERER=inline|lanterna|plain";
            case 5 -> "💡 当前不在 TUI 内编辑 config.json，建议在编辑器里改完重启";
            default -> "(unknown)";
        };
        renderer.stream().println(hint);
    }

    static String handleConfigCommand(PaiCliConfig config, String payload) {
        ProviderConfigUpdate update = parseProviderConfigUpdate(payload);
        if (update.error() != null) {
            return "❌ " + update.error() + "\n" + providerConfigUsage();
        }

        PaiCliConfig.ProviderConfig providerConfig = ensureProviderConfig(config, update.provider());
        if (update.apiKey() != null) {
            providerConfig.setApiKey(update.apiKey());
        }
        if (update.baseUrl() != null) {
            providerConfig.setBaseUrl(update.baseUrl());
        }
        if (update.model() != null) {
            providerConfig.setModel(update.model());
        }
        if (update.loraId() != null) {
            providerConfig.setLoraId(update.loraId());
        }
        if (update.setDefault()) {
            config.setDefaultProvider(update.provider());
        }
        config.save();

        StringBuilder out = new StringBuilder();
        out.append("✅ 已保存 provider 配置: ").append(update.provider()).append('\n');
        out.append("   model: ").append(providerConfig.getModel() == null || providerConfig.getModel().isBlank()
                ? "(默认)" : providerConfig.getModel()).append('\n');
        out.append("   baseUrl: ").append(providerConfig.getBaseUrl() == null || providerConfig.getBaseUrl().isBlank()
                ? "(默认)" : providerConfig.getBaseUrl()).append('\n');
        out.append("   apiKey: ").append(maskSecret(providerConfig.getApiKey())).append('\n');
        if ("xfyun".equals(update.provider())) {
            out.append("   loraId: ").append(providerConfig.getLoraId() == null || providerConfig.getLoraId().isBlank()
                    ? "(未配置)" : providerConfig.getLoraId()).append('\n');
        }
        if (update.setDefault()) {
            out.append("   默认 provider 已设为 ").append(update.provider()).append('\n');
        }
        out.append("   立即切换: /model ").append(update.provider());
        return out.toString();
    }

    static ProviderConfigUpdate parseProviderConfigUpdate(String payload) {
        List<String> args = splitArgs(payload);
        if (args.size() < 2 || !"provider".equalsIgnoreCase(args.get(0))) {
            return ProviderConfigUpdate.error("用法不正确");
        }

        String provider = normalizeProviderName(args.get(1));
        if (!isSupportedProvider(provider)) {
            return ProviderConfigUpdate.error("暂不支持 provider: " + args.get(1));
        }

        String apiKey = null;
        String baseUrl = null;
        String model = null;
        String loraId = null;
        boolean setDefault = false;
        for (int i = 2; i < args.size(); i++) {
            String token = args.get(i);
            if ("--default".equalsIgnoreCase(token) || "--set-default".equalsIgnoreCase(token)) {
                setDefault = true;
                continue;
            }

            String key;
            String value;
            int equals = token.indexOf('=');
            if (equals > 0) {
                key = token.substring(0, equals);
                value = token.substring(equals + 1);
            } else {
                key = token;
                if (i + 1 >= args.size()) {
                    return ProviderConfigUpdate.error("缺少 " + key + " 的值");
                }
                value = args.get(++i);
            }

            switch (normalizeConfigKey(key)) {
                case "api-key" -> apiKey = value;
                case "base-url" -> baseUrl = value;
                case "model" -> model = value;
                case "lora-id" -> loraId = value;
                default -> {
                    return ProviderConfigUpdate.error("未知配置项: " + key);
                }
            }
        }

        if (loraId != null && !"xfyun".equals(provider)) {
            return ProviderConfigUpdate.error("--lora-id 仅支持 xfyun provider");
        }

        if (apiKey == null && baseUrl == null && model == null && loraId == null && !setDefault) {
            return ProviderConfigUpdate.error("至少提供一个配置项");
        }
        return new ProviderConfigUpdate(provider, apiKey, baseUrl, model, loraId, setDefault, null);
    }

    private static String providerConfigUsage() {
        return """
                用法:
                  /config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
                  /config provider freellmapi --model qwen/qwen3-coder:free --default
                  /config provider xfyun --base-url https://maas-api.cn-huabei-1.xf-yun.com/v2 --api-key <key> --model Qwen3.6-35B-A3B --default
                  /config provider xfyun --lora-id <resourceId>
                  /model freellmapi
                  /model xfyun
                """.stripTrailing();
    }

    private static List<String> splitArgs(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }
        return args;
    }

    private static String normalizeConfigKey(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        while (key.startsWith("-")) {
            key = key.substring(1);
        }
        return switch (key) {
            case "apikey", "api_key", "key" -> "api-key";
            case "baseurl", "base_url", "url" -> "base-url";
            case "loraid", "lora_id", "resourceid", "resource_id" -> "lora-id";
            default -> key;
        };
    }

    private static String normalizeProviderName(String raw) {
        String provider = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "stepfun", "step-fun" -> "step";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "free-llm-api", "free_llm_api", "freellm", "free-llm" -> "freellmapi";
            case "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" -> "xfyun";
            default -> provider;
        };
    }

    private static boolean isSupportedProvider(String provider) {
        return List.of("glm", "deepseek", "step", "kimi", "freellmapi", "xfyun").contains(provider);
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "(未配置)";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    static void bindCtrlOToFoldableBlocks(LineReader lineReader, InlineRenderer inline) {
        if (lineReader == null || inline == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-toggle-foldable", () -> {
            inline.toggleLastBlock();
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("paicli-toggle-foldable");
        String ctrlO = String.valueOf((char) 15);  // Ctrl+O
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlO);
            }
        }
    }

    // Ctrl+V 抓系统剪贴板里的图片到 ~/.paicli/cache/ 并把 @image:<path> 注入当前输入行。
    // 失败（无图 / headless / IO 错误）时只打提示，不破坏现有 buffer，覆盖掉 JLine 默认的
    // quoted-insert 没有交互价值。注意 macOS Cmd+V 通常被终端劫持成本地粘贴文本，所以这里
    // 绑的是 Ctrl+V（ASCII 22 / SYN），iTerm / Terminal.app 默认不会拦截。
    //
    // 输入层不按模型名拦截图片：与 Claude Code 类似，先把图片读成附件收进
    // prompt；模型是否接受 image block 由 provider API 自己处理。
    static void bindCtrlVToClipboardImage(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-paste-clipboard-image", () -> {
            ClipboardImage.GrabResult grab = ClipboardImage.grab();
            if (!grab.ok()) {
                lineReader.printAbove("⚠️ Ctrl+V 抓图失败: " + grab.error());
                lineReader.callWidget(LineReader.REDISPLAY);
                return true;
            }
            String token = "@image:<" + grab.path().toAbsolutePath() + "> ";
            lineReader.getBuffer().write(token);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("paicli-paste-clipboard-image");
        String ctrlV = String.valueOf((char) 22);  // Ctrl+V (SYN)
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlV);
            }
        }
    }

    static void bindEscToClearInput(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-clear-input", () -> {
            clearInputBuffer(lineReader);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference clearInput = new Reference("paicli-clear-input");
        String esc = KeyMap.esc();
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(clearInput, esc);
            }
        }
    }

    static void clearInputBuffer(LineReader lineReader) {
        if (lineReader == null || lineReader.getBuffer() == null) {
            return;
        }
        lineReader.getBuffer().clear();
    }

    private static void handleExportCommand(PrintStream out, Agent reactAgent) {
        List<LlmClient.Message> history = reactAgent.getConversationHistory();
        if (!hasExportableMessages(history)) {
            out.println("📭 当前没有对话记录可导出\n");
            return;
        }

        Path exportsDir = Path.of(System.getProperty("user.home"), ".paicli", "exports");
        try {
            Files.createDirectories(exportsDir);
        } catch (IOException e) {
            out.println("❌ 创建导出目录失败: " + e.getMessage() + "\n");
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path exportFile = exportsDir.resolve("session-" + timestamp + ".md");

        String markdown = renderConversationExport(history, LocalDateTime.now());

        try {
            Files.writeString(exportFile, markdown);
            out.println("✅ 对话记录已导出: " + exportFile.toAbsolutePath());
            out.println("   共 " + countExportedMessages(history) + " 条消息\n");
        } catch (IOException e) {
            out.println("❌ 写入导出文件失败: " + e.getMessage() + "\n");
        }
    }

    static boolean hasExportableMessages(List<LlmClient.Message> history) {
        return history != null && history.stream()
                .anyMatch(msg -> msg != null);
    }

    static long countExportedMessages(List<LlmClient.Message> history) {
        if (history == null) {
            return 0;
        }
        return history.stream()
                .filter(msg -> msg != null)
                .count();
    }

    static String renderConversationExport(List<LlmClient.Message> history, LocalDateTime exportedAt) {
        StringBuilder md = new StringBuilder();
        md.append("# PaiCLI 会话导出\n\n");
        md.append("**导出时间**: ").append(exportedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        md.append("---\n\n");

        for (int i = 0; i < history.size(); i++) {
            LlmClient.Message msg = history.get(i);
            if (msg == null) {
                continue;
            }
            String role = msg.role();

            md.append("## ").append(capitalizeRole(role)).append("\n\n");

            // reasoning content
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                md.append("> **思考过程**:\n> \n");
                for (String line : msg.reasoningContent().replace("\r\n", "\n").split("\n")) {
                    md.append("> ").append(line).append("\n");
                }
                md.append("\n");
            }

            // tool calls
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                md.append("**工具调用**:\n\n");
                for (LlmClient.ToolCall tc : msg.toolCalls()) {
                    String toolName = tc.function() != null ? tc.function().name() : "unknown";
                    String toolArgs = tc.function() != null ? tc.function().arguments() : "{}";
                    md.append("- **").append(toolName).append("**:\n");
                    appendFencedBlock(md, formatJsonArg(toolArgs), "json", "  ");
                    md.append("\n");
                }
            }

            // content
            if (msg.content() != null && !msg.content().isBlank()) {
                if ("tool".equals(role)) {
                    String content = msg.content();
                    if (content.length() > 8000) {
                        content = content.substring(0, 8000) + "\n... (已截断，原始长度 " + msg.content().length() + " 字符)";
                    }
                    appendFencedBlock(md, content, "", "");
                    md.append("\n");
                } else {
                    md.append(msg.content()).append("\n\n");
                }
            }
        }
        return md.toString();
    }

    private static void appendFencedBlock(StringBuilder md, String content, String info, String indent) {
        String fence = markdownFenceFor(content);
        md.append(indent).append(fence);
        if (info != null && !info.isBlank()) {
            md.append(info);
        }
        md.append('\n');
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            md.append(indent).append(line).append('\n');
        }
        md.append(indent).append(fence).append("\n");
    }

    static String markdownFenceFor(String content) {
        int longest = 0;
        int current = 0;
        String text = content == null ? "" : content;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    private static String capitalizeRole(String role) {
        return switch (role) {
            case "user" -> "User";
            case "assistant" -> "Assistant";
            case "tool" -> "Tool Result";
            case "system" -> "System";
            default -> role.substring(0, 1).toUpperCase() + role.substring(1);
        };
    }

    private static String formatJsonArg(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private static void printPolicyStatus(PrintStream out, Agent reactAgent) {
        out.println("🛡️ 安全策略状态：");
        out.println("   项目根: " + reactAgent.getToolRegistry().getProjectPath());
        out.println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()) + "，以及所有 mcp__ 前缀工具");
        out.println("   路径围栏: 强制限定在项目根之内（read_file / write_file / list_dir / create_project）");
        out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
        out.println("   写入文件上限: 5MB");
        out.println("   命令执行上限: 60 秒，输出 8KB（截断）");
        out.println("   审计目录: " + reactAgent.getToolRegistry().getAuditLog().getAuditDir());
        out.println();
    }

    static String handleBrowserCommand(String payload,
                                       BrowserSession browserSession,
                                       BrowserConnectivityCheck connectivityCheck,
                                       McpServerManager mcpServerManager,
                                       HitlToolRegistry registry,
                                       HitlHandler hitlHandler) {
        String normalized = payload == null || payload.isBlank() ? "status" : payload.trim();
        String[] parts = normalized.split("\\s+");
        String subCommand = parts[0].toLowerCase();
        return switch (subCommand) {
            case "status" -> browserStatus(browserSession, connectivityCheck, mcpServerManager);
            case "connect" -> {
                if (parts.length >= 2) {
                    int port = parseBrowserPort(parts[1]);
                    yield browserConnectByPort(port, browserSession, connectivityCheck, mcpServerManager, hitlHandler);
                }
                yield browserAutoConnect(browserSession, mcpServerManager, hitlHandler);
            }
            case "disconnect" -> browserDisconnect(browserSession, mcpServerManager, hitlHandler);
            case "tabs" -> browserTabs(browserSession, registry);
            default -> """
                    ❌ 未知 /browser 子命令: %s
                    可用命令：
                      /browser status
                      /browser connect [port]
                      /browser disconnect
                      /browser tabs
                    """.formatted(normalized).trim();
        };
    }

    private static String browserStatus(BrowserSession browserSession,
                                        BrowserConnectivityCheck connectivityCheck,
                                        McpServerManager mcpServerManager) {
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(9222);
        McpServer server = mcpServerManager.server("chrome-devtools");
        String serverStatus = server == null
                ? "未配置"
                : server.status() == McpServerStatus.READY
                ? "● ready (" + server.tools().size() + " tools)"
                : server.status().name().toLowerCase() + (server.errorMessage() == null ? "" : " - " + server.errorMessage());
        String mode = browserSession.mode() == BrowserMode.SHARED
                ? "shared（复用 " + browserSession.browserUrl() + "）"
                : "isolated（临时 user-data-dir，无登录态）";
        return """
                🌐 浏览器会话
                  当前模式: %s
                  chrome-devtools server: %s
                  旧式 /json/version 探活: %s
                  自动连接: Chrome 144+ 可在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging 后使用 /browser connect
                """.formatted(mode, serverStatus, probe.ok() ? "✅ " + probe.browserUrl() : "⚠️ " + probe.message()).trim();
    }

    private static String browserAutoConnect(BrowserSession browserSession,
                                             McpServerManager mcpServerManager,
                                             HitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> autoConnectArgs = List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect");
        String result = mcpServerManager.restartWithArgs("chrome-devtools", autoConnectArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared("autoConnect");
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 已用 --autoConnect 连接 Chrome（需已在 chrome://inspect/#remote-debugging 允许远程调试）\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ autoConnect 连接失败，已回滚 chrome-devtools 启动参数：\n" + result
                + "\n\n请确认 Chrome 144+ 已打开 chrome://inspect/#remote-debugging，并勾选 Allow remote debugging for this browser instance。";
    }

    private static String browserConnectByPort(int port,
                                               BrowserSession browserSession,
                                               BrowserConnectivityCheck connectivityCheck,
                                               McpServerManager mcpServerManager,
                                               HitlHandler hitlHandler) {
        if (port < 1024 || port > 65535) {
            return "❌ /browser connect 端口必须在 1024-65535 之间。默认 /browser connect 使用 --autoConnect；旧式 CDP 端口连接可用 /browser connect 9222。";
        }
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(port);
        if (!probe.ok()) {
            return "❌ 未检测到 Chrome 调试端口 127.0.0.1:" + port + "：" + probe.message() + "\n\n"
                    + chromeLaunchHelp(port);
        }

        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> sharedArgs = List.of("-y", "chrome-devtools-mcp@latest", "--browser-url=" + probe.browserUrl());
        String result = mcpServerManager.restartWithArgs("chrome-devtools", sharedArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared(probe.browserUrl());
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 切换 chrome-devtools server 到 shared 模式 (" + probe.browserUrl() + ")\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ shared 模式切换失败，已回滚 chrome-devtools 启动参数：\n" + result;
    }

    private static String browserDisconnect(BrowserSession browserSession,
                                            McpServerManager mcpServerManager,
                                            HitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            browserSession.switchToIsolated();
            return "❌ 未配置 chrome-devtools MCP server，已清理本地浏览器会话状态";
        }
        String result = mcpServerManager.restartWithArgs(
                "chrome-devtools",
                List.of("-y", "chrome-devtools-mcp@latest", "--isolated=true"));
        browserSession.switchToIsolated();
        hitlHandler.clearApprovedAllForServer("chrome-devtools");
        return "🔄 已切回 isolated 浏览器模式\n" + result;
    }

    private static String browserTabs(BrowserSession browserSession, HitlToolRegistry registry) {
        if (browserSession.mode() != BrowserMode.SHARED) {
            return "当前为 isolated 模式，没有真实 Chrome tab 可复用。可用 /browser connect 切到 shared 模式。";
        }
        return registry.executeTool("mcp__chrome-devtools__list_pages", "{}");
    }

    private static int parseBrowserPort(String value) {
        if (value == null || value.isBlank()) {
            return 9222;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String chromeLaunchHelp(int port) {
        return """
                请先用调试端口启动 Chrome：
                  macOS: open -na "Google Chrome" --args --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                  Windows: start chrome.exe --remote-debugging-port=%d --user-data-dir=%%TEMP%%\\paicli-chrome-profile
                  Linux: google-chrome --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                然后重新执行 /browser connect %d
                """.formatted(port, port, port, port).trim();
    }

    private static void printMcpCommandResult(PrintStream out, String result) {
        out.println(result);
        out.println();
    }

    private static void printAuditTail(PrintStream out, Agent reactAgent, String payload) {
        int requested = parseAuditCount(payload, 10);
        List<AuditLog.AuditEntry> entries = reactAgent.getToolRegistry().getAuditLog().readRecent(requested);
        if (entries.isEmpty()) {
            out.println("📭 今日尚无审计记录\n");
            return;
        }
        out.println("📋 最近 " + entries.size() + " 条危险工具审计：");
        for (AuditLog.AuditEntry entry : entries) {
            out.printf("   [%s] %s %s (%dms, approver=%s)%n",
                    entry.outcome().toUpperCase(),
                    entry.timestamp(),
                    entry.tool(),
                    entry.durationMs(),
                    entry.approver());
            if (entry.reason() != null && !entry.reason().isBlank()) {
                out.println("        原因: " + entry.reason());
            }
            BrowserAuditMetadata metadata = entry.metadata();
            if (metadata != null) {
                out.println("        浏览器: mode=" + metadata.browserMode()
                        + ", sensitive=" + metadata.sensitive()
                        + (metadata.targetUrl() == null ? "" : ", url=" + metadata.targetUrl()));
            }
        }
        out.println();
    }

    private static void printSnapshotCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim().toLowerCase();
        if ("status".equals(normalized)) {
            out.println(snapshotService.status());
            out.println();
            return;
        }
        if ("clean".equals(normalized)) {
            out.println(snapshotService.clean());
            out.println();
            return;
        }
        if (!"list".equals(normalized)) {
            out.println("""
                    ❌ 未知 /snapshot 子命令: %s
                    可用命令：
                      /snapshot
                      /snapshot status
                      /snapshot clean
                      /restore <N>
                    """.formatted(payload).trim());
            out.println();
            return;
        }
        try {
            List<TurnSnapshot> snapshots = snapshotService.listSnapshots(20);
            if (snapshots.isEmpty()) {
                out.println("📭 暂无 Side-Git 快照\n");
                return;
            }
            out.println("📸 最近 " + snapshots.size() + " 条 Side-Git 快照：");
            int preTurnIndex = 0;
            for (TurnSnapshot snapshot : snapshots) {
                String restoreHint = "";
                if ("pre-turn".equals(snapshot.phase().label())) {
                    preTurnIndex++;
                    restoreHint = "  /restore " + preTurnIndex;
                }
                out.printf("   %s %-11s %-18s %s%s%n",
                        snapshot.shortCommitId(),
                        snapshot.phase().label(),
                        snapshot.turnId(),
                        snapshot.createdAt(),
                        restoreHint);
            }
            out.println();
        } catch (Exception e) {
            out.println("❌ 读取快照失败: " + e.getMessage() + "\n");
        }
    }

    private static void printRestoreCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        int offset = parseAuditCount(payload, 1);
        try {
            RestoreResult result = snapshotService.restorePreTurn(offset);
            out.println(result.formatForCli());
            out.println();
        } catch (Exception e) {
            out.println("❌ 恢复快照失败: " + e.getMessage() + "\n");
        }
    }

    private static int parseAuditCount(String payload, int defaultN) {
        if (payload == null || payload.isBlank()) return defaultN;
        try {
            int n = Integer.parseInt(payload.trim());
            return Math.max(1, Math.min(n, 100));
        } catch (NumberFormatException e) {
            return defaultN;
        }
    }

    private static void printStartupHints(PrintStream out) {
        out.println("💡 提示:");
        for (String hint : startupHints()) {
            out.println("   - " + hint);
        }
        out.println();
    }

    private static StartupScreenInfo startupScreenInfo(LlmClient llmClient,
                                                       McpServerManager mcpServerManager,
                                                       SkillRegistry skillRegistry,
                                                       String note) {
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        int total = mcpServerManager.servers().size();
        int tools = mcpServerManager.servers().stream()
                .mapToInt(server -> server.tools().size())
                .sum();
        int skillTotal = skillRegistry.allSkills().size();
        int skillEnabled = skillRegistry.enabledSkills().size();
        return new StartupScreenInfo(
                llmClient.getModelName(),
                llmClient.getProviderName(),
                ready,
                total,
                tools,
                skillEnabled,
                skillTotal,
                note == null ? "" : note.trim()
        );
    }

    private static StatusInfo statusInfo(LlmClient llmClient,
                                         SwitchableHitlHandler hitlHandler,
                                         String phase,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        StatusInfo base = "idle".equals(normalizedPhase)
                ? StatusInfo.idle(llmClient.getModelName(), llmClient.maxContextWindow(), hitlHandler.isEnabled())
                : StatusInfo.active(llmClient.getModelName(), llmClient.maxContextWindow(),
                hitlHandler.isEnabled(), normalizedPhase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    private static StatusInfo statusInfo(Agent reactAgent,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry,
                                         String phase) {
        StatusInfo base = reactAgent.currentStatus(phase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    private static String mcpStatusSummary(McpServerManager mcpServerManager) {
        if (mcpServerManager == null || mcpServerManager.servers().isEmpty()) {
            return "MCP 0";
        }
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        return "MCP " + ready + "/" + mcpServerManager.servers().size();
    }

    private static String skillStatusSummary(SkillRegistry skillRegistry) {
        if (skillRegistry == null || skillRegistry.allSkills().isEmpty()) {
            return "Skill 0";
        }
        return "Skill " + skillRegistry.enabledSkills().size() + "/" + skillRegistry.allSkills().size();
    }

    private static String appendStartupNote(String current, String next) {
        if (next == null || next.isBlank()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isBlank()) {
            return next;
        }
        return current + "\n" + next;
    }

    static Duration mcpStartupWait() {
        String configured = System.getProperty("paicli.mcp.startup.wait.seconds");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_MCP_STARTUP_WAIT_SECONDS");
        }
        if (configured == null || configured.isBlank()) {
            return Duration.ofSeconds(8);
        }
        try {
            long seconds = Long.parseLong(configured.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(8);
        } catch (NumberFormatException ignored) {
            return Duration.ofSeconds(8);
        }
    }

    static String normalizeLineEndings(String rawInput) {
        return rawInput
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String stripBracketedPasteEndMarker(String rawInput) {
        int endMarkerIndex = rawInput.indexOf(BRACKETED_PASTE_END);
        if (endMarkerIndex >= 0) {
            return rawInput.substring(0, endMarkerIndex);
        }
        return rawInput;
    }

    private static boolean isSubmitKey(int key) {
        return key == '\n' || key == '\r';
    }

    static EscapeSequenceType classifyEscapeSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return EscapeSequenceType.STANDALONE_ESC;
        }
        if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
            return EscapeSequenceType.BRACKETED_PASTE;
        }
        if (sequence.startsWith("[") || sequence.startsWith("O")) {
            return EscapeSequenceType.CONTROL_SEQUENCE;
        }
        return EscapeSequenceType.OTHER;
    }

    static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
        if (lineReader == null || sequence == null || sequence.isEmpty()) {
            return "";
        }

        if (isUpArrowSequence(sequence)) {
            return latestHistoryEntry(lineReader.getHistory());
        }

        if (isDownArrowSequence(sequence)) {
            return "";
        }

        return "";
    }

    private static boolean isUpArrowSequence(String sequence) {
        return ARROW_UP.equals(sequence) || APP_ARROW_UP.equals(sequence);
    }

    private static boolean isDownArrowSequence(String sequence) {
        return ARROW_DOWN.equals(sequence) || APP_ARROW_DOWN.equals(sequence);
    }

    private static String latestHistoryEntry(History history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int lastIndex = history.last();
        if (lastIndex < 0) {
            return "";
        }

        String entry = history.get(lastIndex);
        return entry == null ? "" : entry;
    }

    static void configureHistory(LineReader lineReader, Path homeDir) {
        if (lineReader == null) {
            return;
        }
        Path historyFile = resolveHistoryFile(homeDir);
        try {
            Files.createDirectories(historyFile.getParent());
            lineReader.setVariable(LineReader.HISTORY_FILE, historyFile);
            lineReader.setVariable(LineReader.HISTORY_SIZE, historySize());
            lineReader.setVariable(LineReader.HISTORY_FILE_SIZE, historyFileSize());
            lineReader.setOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
            lineReader.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
            lineReader.setOpt(LineReader.Option.HISTORY_REDUCE_BLANKS);
            lineReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            lineReader.getHistory().load();
        } catch (IOException ignored) {
            // History is a convenience feature; failed persistence must not block the CLI.
        }
    }

    static Path resolveHistoryFile(Path homeDir) {
        String configured = firstNonBlank(System.getProperty(HISTORY_FILE_PROPERTY), System.getenv("PAICLI_HISTORY_FILE"));
        if (configured != null) {
            return normalizeHistoryFile(Path.of(configured));
        }
        Path base = homeDir == null ? Path.of(System.getProperty("user.home")) : homeDir;
        return base.resolve(".paicli").resolve("history").resolve(DEFAULT_HISTORY_FILE_NAME)
                .toAbsolutePath().normalize();
    }

    static Path normalizeHistoryFile(Path configured) {
        Path path = configured.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return path.resolve(DEFAULT_HISTORY_FILE_NAME).toAbsolutePath().normalize();
        }
        return path;
    }

    static void clearLineReaderHistory(LineReader lineReader) {
        if (lineReader == null || lineReader.getHistory() == null) {
            return;
        }
        try {
            lineReader.getHistory().purge();
        } catch (IOException ignored) {
            // Keep command behavior simple: in-memory history may still be reset by JLine.
        }
    }

    private static int historySize() {
        return configuredPositiveInt(HISTORY_SIZE_PROPERTY, "PAICLI_HISTORY_SIZE", 2_000);
    }

    private static int historyFileSize() {
        return configuredPositiveInt(HISTORY_FILE_SIZE_PROPERTY, "PAICLI_HISTORY_FILE_SIZE", 10_000);
    }

    private static int configuredPositiveInt(String property, String env, int fallback) {
        String raw = firstNonBlank(System.getProperty(property), System.getenv(env));
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static PlanExecuteAgent.PlanReviewDecision mapReviewDecision(PlanReviewInputParser.Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> PlanExecuteAgent.PlanReviewDecision.execute();
            case CANCEL -> PlanExecuteAgent.PlanReviewDecision.cancel();
            case SUPPLEMENT -> PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
        };
    }

    /**
     * 从 .env 文件加载 API Key
     */
    private static String loadApiKey() {
        return loadConfigValue("GLM_API_KEY", null);
    }

    private static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "PAICLI_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".paicli", "logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "PAICLI_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "PAICLI_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "PAICLI_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "PAICLI_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("⚠️ 创建日志目录失败: " + e.getMessage());
        }
    }

    private static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = System.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            configuredValue = loadConfigValue(envKey, defaultValue);
        }
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    private static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static String loadConfigValue(String key, String defaultValue) {
        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue.trim();
        }

        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File currentEnv = new File(ENV_FILE);
        if (currentEnv.exists()) {
            String value = readValueFromFile(currentEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        File homeEnv = new File(System.getProperty("user.home"), ENV_FILE);
        if (homeEnv.exists()) {
            String value = readValueFromFile(homeEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return defaultValue;
    }

    private static String readValueFromFile(File file, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    static ModelSelection resolveModelSelection(String raw) {
        String value = raw == null ? "" : raw.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "glm" -> new ModelSelection("glm", "glm-5.1", true);
            case "deepseek" -> new ModelSelection("deepseek", null, false);
            case "step", "stepfun", "step-fun" -> new ModelSelection("step", null, false);
            case "kimi", "moonshot", "moonshotai", "moonshot-ai" -> new ModelSelection("kimi", null, false);
            case "freellmapi", "free-llm-api", "free_llm_api", "freellm", "free-llm" ->
                    new ModelSelection("freellmapi", null, false);
            case "xfyun", "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" ->
                    new ModelSelection("xfyun", null, false);
            default -> {
                if (normalized.startsWith("glm-")) {
                    yield new ModelSelection("glm", value, true);
                }
                if (normalized.startsWith("deepseek")) {
                    yield new ModelSelection("deepseek", value, true);
                }
                if (normalized.startsWith("step")) {
                    yield new ModelSelection("step", value, true);
                }
                if (normalized.startsWith("kimi-") || normalized.startsWith("moonshot-")) {
                    yield new ModelSelection("kimi", value, true);
                }
                yield new ModelSelection(normalized, null, false);
            }
        };
    }

    private static PaiCliConfig.ProviderConfig ensureProviderConfig(PaiCliConfig config, String provider) {
        if (config.getProviders() == null) {
            config.setProviders(new LinkedHashMap<>());
        }
        return config.getProviders().computeIfAbsent(provider, ignored -> new PaiCliConfig.ProviderConfig());
    }

    private static void printStartupScreen(PrintStream out, StartupScreenInfo info) {
        for (String line : startupScreenLines(info)) {
            out.println(line);
        }
    }

    static List<String> startupScreenLines(StartupScreenInfo info) {
        List<String> lines = new ArrayList<>(startupBannerLines(info));
        lines.add("");
        return lines;
    }

    static List<String> startupBannerLines() {
        return startupBannerLines(new StartupScreenInfo(
                "auto",
                "model",
                0,
                0,
                0,
                0,
                0,
                ""));
    }

    static List<String> startupBannerLines(StartupScreenInfo info) {
        String model = info.model() == null || info.model().isBlank() ? "auto" : info.model();
        String provider = info.provider() == null || info.provider().isBlank() ? "model" : info.provider();
        String mcp = info.mcpTotal() <= 0
                ? "MCP not configured"
                : "MCP " + info.mcpReady() + "/" + info.mcpTotal() + " · " + info.mcpTools() + " tools";
        String skills = info.skillsTotal() <= 0
                ? "0 skills"
                : info.skillsEnabled() + "/" + info.skillsTotal() + " skills";
        String ready = "Model " + model + " (" + provider + ")";
        String capabilities = "ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG";
        String state = mcp + " · " + skills + " · ReAct";
        List<String> lines = new ArrayList<>(List.of(
                "   " + AnsiStyle.section("██████████") + "    " + AnsiStyle.emphasis("PaiCLI") + " " + AnsiStyle.section("π") + "  " + AnsiStyle.subtle("v" + VERSION),
                "   " + AnsiStyle.section("  ██  ██") + "    " + AnsiStyle.subtle(ready),
                "   " + AnsiStyle.section("  ██  ██") + "    " + AnsiStyle.subtle(state),
                "   " + AnsiStyle.section("  ██  ██") + "    " + AnsiStyle.subtle(capabilities),
                "   " + AnsiStyle.section("  ██  ██"),
                "",
                "Tips for getting started:",
                "1. Type " + AnsiStyle.emphasis("/") + " for commands and Tab completion",
                "2. Ask coding questions, edit code or run commands",
                "3. Attach context with " + AnsiStyle.emphasis("@path") + " or " + AnsiStyle.emphasis("@image:")
        ));
        if (info.note() != null && !info.note().isBlank()) {
            lines.add("");
            lines.add(AnsiStyle.subtle(info.note().replace('\n', ' ')));
        }
        return lines;
    }

    static McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        Path configFile = userHome.resolve(".paicli").resolve("mcp.json");
        if (Files.notExists(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CHROME_DEVTOOLS_MCP_JSON);
            return new McpConfigBootstrapResult(true,
                    "✅ 已创建默认 MCP 配置: " + configFile
                            + "\n   默认启用 chrome-devtools（isolated 模式）。");
        }
        String content = Files.readString(configFile);
        if (!content.contains("\"chrome-devtools\"")) {
            return new McpConfigBootstrapResult(false,
                    "ℹ️ 检测到 ~/.paicli/mcp.json 未配置 chrome-devtools，建议参考 README 添加浏览器 MCP server。");
        }
        return new McpConfigBootstrapResult(false, "");
    }

    private static MemorySaveRequest parseMemorySave(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.regionMatches(true, 0, "--global ", 0, 9)) {
            return new MemorySaveRequest(value.substring(9).trim(), "global");
        }
        if (value.equalsIgnoreCase("--global")) {
            return new MemorySaveRequest("", "global");
        }
        if (value.regionMatches(true, 0, "--project ", 0, 10)) {
            return new MemorySaveRequest(value.substring(10).trim(), "project");
        }
        if (value.equalsIgnoreCase("--project")) {
            return new MemorySaveRequest("", "project");
        }
        return new MemorySaveRequest(value, "project");
    }

    private static String formatMemoryEntries(String title, List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder(title).append("：\n");
        if (entries == null || entries.isEmpty()) {
            return sb.append("📭 没有匹配的长期记忆。").toString();
        }
        for (MemoryEntry entry : entries) {
            String scope = LongTermMemory.scopeOf(entry);
            String project = entry.getMetadata().get("project");
            sb.append("- ")
                    .append(entry.getId())
                    .append(" [").append(scope).append("]");
            if ("project".equals(scope) && project != null && !project.isBlank()) {
                sb.append(" ").append(shortenPath(project));
            }
            sb.append(" · ").append(entry.getTimestamp()).append("\n")
                    .append("  ").append(entry.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private static String shortenPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path p = Path.of(path);
            int count = p.getNameCount();
            if (count <= 3) {
                return path;
            }
            return "..." + File.separator + p.subpath(count - 3, count);
        } catch (Exception e) {
            return path;
        }
    }

    record McpConfigBootstrapResult(boolean created, String message) {
    }

    record ModelSelection(String provider, String model, boolean explicitModel) {
    }

    record ProviderConfigUpdate(String provider, String apiKey, String baseUrl, String model, String loraId,
                                boolean setDefault, String error) {
        static ProviderConfigUpdate error(String error) {
            return new ProviderConfigUpdate(null, null, null, null, null, false, error);
        }
    }

    private record MemorySaveRequest(String fact, String scope) {
    }
}
