package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.jsonrpc.JsonRpcClient;
import com.paicli.mcp.jsonrpc.JsonRpcException;
import com.paicli.mcp.protocol.McpCallToolRequest;
import com.paicli.mcp.protocol.McpCallToolResult;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.protocol.McpSchemaSanitizer;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.tool.ToolOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCP 协议的业务门面（客户端侧）。
 *
 * 分层关系（自顶向下，各管一层）：
 *   McpServerManager  —— 编排层：管"何时启动 server、调哪个工具、怎么注册"
 *   McpClient         —— 业务层（本类）：管"MCP 有哪些操作"，把协议方法封装成可读 Java 方法
 *   JsonRpcClient     —— 协议层：管"JSON-RPC 机制"，发请求/生成 id/按 id 匹配响应/超时/分发通知
 *   McpTransport      —— 传输层：管"具体字节怎么走"（stdio 子进程 / Streamable HTTP）
 *
 * 本类职责：把 MCP 方法（initialize / tools/list / tools/call / resources/list ...）
 * 封装成业务方法，并统一做参数构造、结果解析、Schema 清洗、错误转字符串。
 * 它不关心 JSON-RPC 帧怎么收发（JsonRpcClient 的事），也不关心字节怎么传输（transport 的事）。
 */
public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;   // initialize 握手默认 60 秒超时
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "paicli.mcp.initialize.timeout.seconds";   // 超时系统属性名
    private static final String INITIALIZE_TIMEOUT_ENV = "PAICLI_MCP_INITIALIZE_TIMEOUT_SECONDS";        // 超时环境变量名

    private final String serverName;   // 当前 MCP server 的名字（用于生成 mcp__server__tool 命名空间）
    private final JsonRpcClient rpc;   // JSON-RPC 客户端：McpClient 拿它发请求，拼 JSON 帧、管 id 配对、等响应、处理超时都是它的事
    private final McpTransport transport;   // 传输通道（stdio / HTTP），用于拿 stderr、进程 id、传输名
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();   // initialize 后缓存的 server 能力声明

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);   // JsonRpcClient 绑定到同一个 transport 上，通过它收发 JSON-RPC 帧
    }

    /**
     * MCP 握手：先发 initialize 请求拿到 server 能力（capabilities），
     * 再回发 notifications/initialized 通知，server 才认为握手完成。
     */
    public void initialize() throws IOException {
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), initializeTimeoutSeconds());   // 发 initialize 请求
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode() : result.path("capabilities");   // 缓存 capabilities（判断支持 resources/prompts 否）
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());   // 回发 initialized 通知（MCP 规定必须回）
    }

    static int initializeTimeoutSeconds() {
        String configured = System.getProperty(INITIALIZE_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(INITIALIZE_TIMEOUT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            return seconds > 0 ? seconds : DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
    }

    public boolean supportsResources() {
        return serverCapabilities.has("resources");
    }

    public boolean supportsPrompts() {
        return serverCapabilities.has("prompts");
    }

    /**
     * 拉取该 server 暴露的工具列表（发 tools/list），并做两件收口：
     * ① Schema 清洗（McpSchemaSanitizer 去掉 $ref/anyOf/超长 description）；
     * ② 生成命名空间名 mcp__server__tool，避免不同 server 的同名工具冲突。
     */
    public List<McpToolDescriptor> listTools() throws IOException {
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);   // 发 tools/list，30 秒超时
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {   // 返回结构异常，返回空列表
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");   // 工具名
            if (name.isBlank()) {
                continue;   // 名字为空的跳过
            }
            String description = tool.path("description").asText("");   // 工具描述（给模型看）
            JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));   // 清洗参数 schema，降低模型调用失败率
            descriptors.add(new McpToolDescriptor(
                    serverName,                       // 原始 server 名
                    name,                             // 原始工具名
                    McpToolDescriptor.namespaced(serverName, name),   // 命名空间名：mcp__server__tool
                    description,
                    schema
            ));
        }
        return descriptors;
    }

    /** 调 MCP 工具（字符串版），只取文本结果，图片等附加内容丢弃。 */
    public String callTool(String toolName, String argumentsJson) throws IOException {
        return callToolOutput(toolName, argumentsJson).text();
    }

    /**
     * 调 MCP 工具（完整版）：发 tools/call，解析 result，并处理 isError。
     * 这是 MCP 工具运行时的真正入口——LLM 调 mcp__xxx 时最终走到这里。
     */
    public ToolOutput callToolOutput(String toolName, String argumentsJson) throws IOException {
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {   // 无参数用空对象
            args = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);   // 把 LLM 给的参数 JSON 字符串解析成树
        }
        ObjectNode params = McpCallToolRequest.toJson(toolName, args);   // 拼 tools/call 的参数（name + arguments）
        JsonNode result = rpc.request("tools/call", params, 60);   // 发 tools/call，60 秒超时
        McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);   // 解析返回结构
        ToolOutput output = callResult.toToolOutput();   // 转成统一的 ToolOutput（文本 + 图片）
        if (callResult.isError()) {   // server 返回 isError=true
            return new ToolOutput("MCP 工具返回错误: " + output.text(), output.imageParts());   // 包一层错误提示
        }
        return output;
    }

    /** 拉取该 server 暴露的 resources 列表（发 resources/list）。server 不支持该方法（-32601）时返回空列表。 */
    public List<McpResourceDescriptor> listResources() throws IOException {
        try {
            JsonNode result = rpc.request("resources/list", JsonNodeFactory.instance.objectNode(), 30);   // 发 resources/list
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) {
                return List.of();
            }
            List<McpResourceDescriptor> descriptors = new ArrayList<>();
            for (JsonNode resource : resources) {
                McpResourceDescriptor descriptor = McpResourceDescriptor.fromJson(serverName, resource);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
            return descriptors;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {   // -32601 = method not found：server 不支持 resources，优雅降级返回空
                return List.of();
            }
            throw e;
        }
    }

    /** 读取某个 resource 的内容（发 resources/read，参数是 uri）。 */
    public List<McpResourceContent> readResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);   // 参数：要读的 resource uri
        JsonNode result = rpc.request("resources/read", params, 60);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) {
            return List.of();
        }
        List<McpResourceContent> resourceContents = new ArrayList<>();
        for (JsonNode content : contents) {
            McpResourceContent resourceContent = McpResourceContent.fromJson(content);
            if (resourceContent != null) {
                resourceContents.add(resourceContent);
            }
        }
        return resourceContents;
    }

    /** 订阅某个 resource 的变更通知（发 resources/subscribe）。 */
    public void subscribeResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        rpc.request("resources/subscribe", params, 30);
    }

    /** 拉取该 server 暴露的 prompts 列表（发 prompts/list），返回可读的展示文本。 */
    public List<String> listPrompts() throws IOException {
        try {
            JsonNode result = rpc.request("prompts/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String title = prompt.path("title").asText("");
                String description = prompt.path("description").asText("");
                String display = title.isBlank() ? name : title + " (" + name + ")";   // 有 title 用 "title (name)"，否则用 name
                lines.add(description.isBlank() ? display : display + " - " + description);
            }
            return lines;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {   // server 不支持 prompts，返回空
                return List.of();
            }
            throw e;
        }
    }

    /** 注册一个被动通知监听器（如 tools/list_changed），转发给底层 JsonRpcClient。 */
    public void onNotification(Consumer<JsonNode> listener) {
        rpc.onNotification(listener);
    }

    public static String formatResources(List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return "📭 该 MCP server 暂无 resources";
        }
        StringBuilder sb = new StringBuilder("📚 MCP resources（").append(resources.size()).append("）\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- ").append(resource.uri());
            String name = resource.displayName();
            if (name != null && !name.isBlank() && !name.equals(resource.uri())) {
                sb.append(" | ").append(name);
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" | ").append(resource.mimeType());
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("\n  ").append(resource.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "📭 MCP resource 内容为空";
        }
        StringBuilder sb = new StringBuilder();
        for (McpResourceContent content : contents) {
            String mimeType = content.mimeType() == null || content.mimeType().isBlank()
                    ? "application/octet-stream"
                    : content.mimeType();
            sb.append("<resource uri=\"").append(escapeXml(content.uri()))
                    .append("\" mimeType=\"").append(escapeXml(mimeType)).append("\">\n");
            if (content.isText()) {
                sb.append(content.text());
            } else {
                sb.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            sb.append("\n</resource>\n");
        }
        return sb.toString().trim();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** 透传 transport 收集的 stderr 日志（供 /mcp logs 查看排错）。 */
    public List<String> stderrLines() {
        return transport.stderrLines();
    }

    /** 透传子进程的 PID（stdio 传输才有，HTTP 传输返回 null）。 */
    public Long processId() {
        return transport.processId();
    }

    /** 透传传输类型名（"stdio" 或 http 相关标识）。 */
    public String transportName() {
        return transport.transportName();
    }

    @Override
    public void close() {
        // 直接走 transport-level 关闭信号：stdio 通过 stdin EOF + 进程销毁；HTTP 通过 DELETE session。
        // 之前会先发 shutdown notification，但当 server 卡死 / 队列堵塞时这条通知会让 close 阻塞 60 秒。
        // 移除后退出更快、行为更可预期；shutdown 语义改由 transport 层承担。
        rpc.close();
    }
}
