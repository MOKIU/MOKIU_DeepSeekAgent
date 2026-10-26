# PaiCLI · 类 Claude Code 的 Java 终端智能体

一个运行在终端里的 AI Agent：用自然语言驱动，直接读写工程、执行命令、检索代码、调用 MCP 工具，并在危险操作前请求人工确认。

- **语言 / 构建**：Java 17 + Maven
- **交互形态**：JLine 终端（inline 模式）+ 可选 Lanterna TUI
- **当前版本**：v16.1.0（构建产物 `paicli-1.0-SNAPSHOT.jar`）
- **演进状态**：已交付 23 期能力，规划见 [`ROADMAP.md`](ROADMAP.md)

---

## 主要特性

| 能力 | 说明 |
| --- | --- |
| 三种执行模式 | ReAct 单代理、Plan-and-Execute（DAG 计划）、Multi-Agent（多角色协作） |
| 代码库理解 | `glob_files` / `grep_code` / `read_file` 实时探索；RAG 语义检索作为补充 |
| 工具调用 | 11 个内置工具 + MCP 动态工具，支持并行执行（默认最多 4 并发，结果保序） |
| 人工审批（HITL） | 危险操作弹窗确认，支持“本次全部放行”“按 MCP server 放行”“改参数后执行” |
| 安全策略 | PathGuard 限定项目根，CommandGuard 拦截破坏性命令，操作写入 JSONL 审计 |
| 上下文管理 | 短期记忆压缩 + 会话历史压缩，token 预算与状态栏实时显示 |
| Side-Git 快照 | 每轮对话生成工作区快照，可 `/restore` 回滚 |
| 联网 | `web_search` / `web_fetch`，可接 StepSearch MCP 或 SearXNG/SerpAPI |
| 浏览器 | 基于 Chrome DevTools MCP 的页面读取与操作，敏感页面额外审批 |
| MCP | stdio / Streamable HTTP 传输，支持 resources 与 `@server:uri` 引用 |
| 其他 | LSP 诊断注入、图片输入、微信 iLink 通道、Runtime API + 持久任务 |

---

## 环境要求

- JDK 17 及以上
- Maven 3.8+
- 可选：`ripgrep`（`grep_code` 优先使用，缺失时回退 Java 扫描）
- 至少一个模型 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY`

---

## 快速开始

```bash
# 1. 准备配置（Windows 用 copy）
cp .env.example .env
#    编辑 .env，至少填入一个 *_API_KEY

# 2. 构建（默认跳过测试，产出可运行的 fat jar）
mvn clean package

# 3. 启动
java -jar target/paicli-1.0-SNAPSHOT.jar
```

进入交互界面后直接输入任务即可，例如：

```text
帮我看看 ToolRegistry 里 executeTool 的调用链，并总结几个可以优化的点
```

> `.env` 含密钥，已在 `.gitignore` 中排除，**不要提交**。

---

## 配置

配置来自项目根目录 `.env`（不存在时回退代码默认值），常用项：

| 变量 | 作用 |
| --- | --- |
| `GLM_API_KEY` / `GLM_MODEL` | 智谱 GLM（默认 provider，默认模型 `glm-5.1`） |
| `DEEPSEEK_API_KEY` / `DEEPSEEK_MODEL` | DeepSeek（`/model deepseek` 切换后使用） |
| `STEP_API_KEY` / `STEP_BASE_URL` / `STEP_MODEL` | 阶跃星辰 StepFun |
| `KIMI_API_KEY` / `KIMI_MODEL` | Kimi / Moonshot |
| `FREELLMAPI_API_KEY` | FreeLLMAPI 聚合通道 |
| `EMBEDDING_PROVIDER` / `EMBEDDING_MODEL` / `EMBEDDING_API_KEY` | RAG 向量化 |
| `SEARCH_PROVIDER` / `SERPAPI_KEY` / `SEARXNG_URL` | 联网搜索后端 |
| `PAICLI_RENDERER` / `PAICLI_TUI` / `PAICLI_NO_STATUSBAR` | 渲染与界面开关 |
| `PAICLI_LOG_DIR` / `PAICLI_LOG_LEVEL` | 日志目录与级别 |
| `PAICLI_SNAPSHOT_ENABLED` / `PAICLI_SNAPSHOT_DIR` / `PAICLI_SNAPSHOT_MAX` | Side-Git 快照 |
| `PAICLI_TASK_DIR` / `PAICLI_TASK_WORKERS` | 后台持久任务 |
| `PAICLI_RUNTIME_API_KEY` / `PAICLI_RUNTIME_DIR` | Runtime API 鉴权与数据目录 |
| `PAICLI_LSP_ENABLED` / `PAICLI_LSP_MAX_DIAGNOSTICS` | LSP 诊断注入 |
| `PAICLI_AUDIT_DIR` | 审计日志目录（默认 `~/.paicli/audit`） |

完整键名与注释见 [`.env.example`](.env.example)。

---

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `/model [provider]` | 查看或切换模型 provider |
| `/plan <任务>` | 进入 Plan-and-Execute 模式（生成 DAG 计划后可审阅） |
| `/team <任务>` | 进入 Multi-Agent 模式 |
| `/hitl on\|off` | 开关人工审批 |
| `/memory list\|search\|clear` | 长期记忆管理（`/save` 保存当前会话要点） |
| `/save` | 把本轮稳定事实写入长期记忆 |
| `/init` | 生成项目级记忆 `PAI.md` |
| `/index` / `/search` / `/graph` | 代码索引、语义检索与依赖图 |
| `/context` | 查看当前上下文与 token 占用 |
| `/compact` / `/clear` | 压缩会话历史 / 清空当前会话 |
| `/snapshot` / `/restore` | 查看与恢复 Side-Git 快照 |
| `/audit` / `/policy` | 查看审计记录与当前安全策略 |
| `/browser ...` | 浏览器会话管理 |
| `/mcp ...` | 查看 MCP server 状态与工具 |
| `/skill ...` | 技能列表、重载 |
| `/task ...` | 后台持久任务管理 |
| `/wechat` | 扫码绑定微信 iLink 通道 |
| `/export` | 导出当前会话（含 system prompt）为 Markdown |
| `/history clear` | 清空本机输入历史 |
| `/exit` `/quit` | 退出 |

---

## 项目结构

```text
src/main/java/com/paicli/
├── agent/       Agent（ReAct）、PlanExecuteAgent、SubAgent、AgentOrchestrator
├── cli/         Main、命令解析、补全、历史
├── llm/         LlmClient 与各 provider 客户端、ClientFactory
├── tool/        ToolRegistry（内置工具与执行）
├── hitl/        审批请求/结果、审批策略、终端审批
├── policy/      PathGuard、CommandGuard、AuditLog
├── memory/      短期记忆、长期记忆、历史压缩、TokenBudget
├── plan/        Planner、ExecutionPlan、Task（DAG）
├── rag/         CodeIndex、CodeChunker、VectorStore、CodeRetriever
├── mcp/         MCP 客户端、server 管理、transport/resources
├── snapshot/    Side-Git 快照与恢复
├── runtime/     Runtime API（线程/回合/事件）与持久任务
├── render/      渲染器（inline / plain / TUI 适配）
├── tui/         Lanterna TUI
├── prompt/      Prompt 分层组装
├── skill/       技能注册、索引与上下文注入
├── lsp/         LSP 诊断
├── browser/     浏览器会话、敏感页面策略
├── web/         搜索与网页抓取
├── image/       图片输入
├── wechat/      微信 iLink 通道
├── config/      配置读取
├── context/     上下文画像与 token 统计
└── util/        工具类
```

### 三条执行路径

| 路径 | 入口 | 触发 |
| --- | --- | --- |
| ReAct | `agent/Agent.java` | 默认 |
| Plan-and-Execute | `agent/PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `agent/AgentOrchestrator.java` | `/team` |

### 内置工具

`read_file`、`write_file`、`list_dir`、`glob_files`、`grep_code`、`execute_command`、`create_project`、`search_code`、`web_search`、`web_fetch`、`revert_turn`

MCP 工具命名为 `mcp__{server}__{tool}`，可通过 `.paicli/mcp.json` 或 `~/.paicli/mcp.json` 配置。

---

## 安全模型

```text
HitlToolRegistry（审批）
    → ToolRegistry（执行）
        → PathGuard / CommandGuard（策略兜底）
            → AuditLog（JSONL 审计）
```

- 写文件、执行命令、回滚、MCP 工具默认需要审批；
- 用户无法批准被策略层拒绝的请求；
- 所有写操作与拒绝记录落 `~/.paicli/audit/audit-YYYY-MM-DD.jsonl`。

---

## 运行数据目录

| 路径 | 内容 |
| --- | --- |
| `~/.paicli/memory/` | 长期记忆（SQLite） |
| `~/.paicli/history/` | 输入历史 |
| `~/.paicli/audit/` | 审计日志 |
| `~/.paicli/snapshots/` | Side-Git 快照 |
| `~/.paicli/exports/` | `/export` 导出 |
| `.paicli/mcp.json` | 项目级 MCP 配置 |
| `PAI.md` / `.paicli/PAI.md` | 项目级记忆（启动自动注入） |

---

## 测试

```bash
mvn test -Pquick            # 常规回归（跳过外部进程/网络类慢测试）
mvn test -Pphase16-smoke    # TUI / 渲染相关
mvn test -Dtest=ToolRegistryTest -DskipTests=false   # 针对性
mvn test -DskipTests=false  # 全量
```

---

## 来源与致谢

本项目基于开源项目 **PaiCLI**（作者：沉默王二，<https://gitcode.com/javabetter/paicli>）整理与二次开发，沿用其架构与大部分实现；上游仓库未附带 LICENSE 文件，使用与再分发前请自行确认授权。

---

## 说明

- 构建产物在 `target/`，已被 `.gitignore` 排除；
- 行为细节以代码为准，`AGENTS.md` 记录了当前架构约定与协作规则；
- 功能演进方向见 `ROADMAP.md`（其中的“将来”不代表已实现）。
