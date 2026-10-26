package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.protocol.McpInitializeRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * MCP 的 Streamable HTTP 传输实现（HTTP 支路）。
 *
 * 与 stdio 不同：stdio 靠子进程 stdin/stdout，这里靠 HTTP 请求/响应。
 * 核心机制：
 *   1. 每个请求 POST 到 server 的 url，带 MCP-Protocol-Version 头；
 *   2. 用 Mcp-Session-Id 头维持会话（首次响应返回 sessionId，后续请求带上去）；
 *   3. 响应可能是普通 JSON（单条），也可能是 SSE 流（text/event-stream，多条），分别解析后分发给监听器；
 *   4. close 时发 DELETE 请求结束会话（best-effort，短超时）。
 */
public class StreamableHttpTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient client = new OkHttpClient.Builder()   // HTTP 客户端（OkHttp）
            .connectTimeout(30, TimeUnit.SECONDS)   // 建连超时 30 秒
            .readTimeout(60, TimeUnit.SECONDS)      // 读超时 60 秒（SSE 长响应可能较久）
            .writeTimeout(30, TimeUnit.SECONDS)     // 写超时 30 秒
            .callTimeout(60, TimeUnit.SECONDS)      // 整次调用超时 60 秒
            .build();
    private final String url;                       // server 的端点地址（如 https://xxx/v1）
    private final Map<String, String> headers;      // 额外的自定义请求头（如 Authorization）
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();   // 收到消息时的监听器列表（线程安全）
    private volatile String sessionId;              // MCP 会话 id，首次响应里拿到，后续请求带上

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);   // headers 不可变拷贝，防空
    }

    /**
     * 发送一条 JSON-RPC 消息：POST 到 server，解析响应（JSON 或 SSE），分发给监听器。
     */
    @Override
    public void send(JsonNode message) throws IOException {
        RequestBody body = RequestBody.create(MAPPER.writeValueAsString(message), JSON);   // 消息序列化成 JSON 请求体
        Request.Builder builder = new Request.Builder()
                .url(url)                                                      // 目标端点
                .header("Content-Type", "application/json")                   // 请求体是 JSON
                .header("Accept", "application/json, text/event-stream")      // 接受 JSON 或 SSE 两种响应
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)   // MCP 协议版本头
                .post(body);                                                  // POST 请求
        headers.forEach(builder::header);                                     // 追加自定义头（如 Authorization）
        if (sessionId != null && !sessionId.isBlank()) {                      // 已有会话 id
            builder.header("Mcp-Session-Id", sessionId);                      // 带上会话 id（复用会话）
        }

        try (Response response = client.newCall(builder.build()).execute()) {   // 发起请求（try-with-resources 自动关响应）
            String newSession = response.header("Mcp-Session-Id");              // 从响应头拿 server 分配的会话 id
            if (newSession != null && !newSession.isBlank()) {                  // server 返回了新会话 id
                sessionId = newSession;                                         // 保存，供后续请求复用
            }
            if (!response.isSuccessful()) {                                     // 非 2xx
                throw new IOException("HTTP " + response.code() + " " + response.message());   // 抛异常
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {                                         // 空响应体
                return;                                                         // 直接返回
            }
            String contentType = response.header("Content-Type", "");           // 判断响应类型
            String raw = responseBody.string();                                 // 读响应体文本
            // notification 路径下 server 可以返回 202 + 空 body 或 200 + 空 body。
            // 这里 swallow 空响应，避免 Jackson 对空字符串抛 MismatchedInputException。
            if (raw == null || raw.isBlank()) {                                 // 空响应
                return;                                                         // 忽略，避免 Jackson 解析空串报错
            }
            List<JsonNode> messages = contentType.contains("text/event-stream")   // 如果是 SSE 流
                    ? parseSse(raw)                                               // 用 SSE 解析器拆成多条
                    : List.of(MAPPER.readTree(raw));                              // 否则按单条 JSON 解析
            for (JsonNode node : messages) {                                      // 遍历解析出的每条消息
                for (Consumer<JsonNode> listener : listeners) {                   // 分发给所有监听器
                    listener.accept(node);
                }
            }
        }
    }

    /** 注册一个收消息监听器（收到 server 推送/响应时回调）。 */
    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 传输类型名，用于 /mcp 状态展示。 */
    @Override
    public String transportName() {
        return "http";
    }

    /** 关闭会话：发 DELETE 请求结束 server 侧 session（best-effort，失败不影响退出）。 */
    @Override
    public void close() {
        if (sessionId == null || sessionId.isBlank()) {   // 没有会话 id（从未成功建立会话）
            return;                                       // 无需关闭
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)   // 带会话 id，告诉 server 关哪个会话
                .delete();                             // DELETE 请求
        headers.forEach(builder::header);              // 追加自定义头
        // close 是 best-effort：server 已经关停 / 网络不通时不应该让 PaiCLI 退出卡住。
        // 主 client 的 callTimeout 是 60s，这里用 5s 短超时单独发请求。
        OkHttpClient closeClient = client.newBuilder()   // 单独建一个短超时 client（不动主 client）
                .callTimeout(5, TimeUnit.SECONDS)        // 整次调用 5 秒超时
                .readTimeout(5, TimeUnit.SECONDS)        // 读 5 秒
                .connectTimeout(2, TimeUnit.SECONDS)     // 建连 2 秒
                .build();
        try (Response ignored = closeClient.newCall(builder.build()).execute()) {   // 发 DELETE
            // best effort  // 成功也不关心结果
        } catch (IOException ignored) {   // 失败（网络不通等）直接吞掉
        }
    }

    /**
     * 解析 SSE（Server-Sent Events）流：按 "data:" 行累积，空行表示一条消息结束。
     * 例：data: {...}\n\n  →  一条 JSON 消息。
     */
    private static List<JsonNode> parseSse(String raw) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();   // 累积当前这条消息的 data
        for (String line : raw.split("\\R")) {      // 按行拆分（\R 匹配所有换行符）
            if (line.isBlank()) {                   // 空行 = 一条 SSE 消息结束
                if (!data.isEmpty()) {              // 有累积的 data
                    messages.add(MAPPER.readTree(data.toString()));   // 把 data 解析成 JSON 加入结果
                    data.setLength(0);              // 清空，准备下一条
                }
                continue;
            }
            if (line.startsWith("data:")) {         // 只处理 data: 行（忽略 event:/id:/comment 行）
                if (!data.isEmpty()) data.append('\n');   // 多行 data 用换行连接
                data.append(line.substring("data:".length()).trim());   // 去掉 "data:" 前缀
            }
        }
        if (!data.isEmpty()) {                      // 结尾没有空行时的最后一条
            messages.add(MAPPER.readTree(data.toString()));
        }
        return messages;
    }
}
