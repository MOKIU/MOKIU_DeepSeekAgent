package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>(); // 请求-响应的挂号登记簿,当 request() 发出一个 JSON-RPC 请求时，系统会给这个请求生成一个唯一的 id，然后把“还没拿到结果的承诺书”（CompletableFuture）放进这个 pending Map 里寄存起来,当后台线程收到 MCP Server 的响应时，handleMessage 会从响应里取出 id，然后去 pending 里把这个对应的 future 取出来（移除掉），并塞给它结果（或异常）
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paicli-mcp-jsonrpc-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage); // 把自己处理消息的方法（handleMessage）作为“回调函数”注册给了底层的 Transport。一旦 Transport 从 MCP Server 收到任何数据，就会主动调用这个回调，把数据“推”回给 JsonRpcClient
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        try {
            transport.send(request);
            return future.get(timeoutSeconds + 1, TimeUnit.SECONDS);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    /**
     * 处理 transport 回调进来的每一条 JSON-RPC 消息。
     * 这是"收到 server 消息"的最终落地处：按有没有 id 区分通知和响应。
     */
    private void handleMessage(JsonNode message) {
        JsonNode idNode = message.get("id");                     // 取消息的 id 字段
        if (idNode == null || idNode.isNull()) {                 // 没有 id → 是"通知"（单向，无需响应）
            for (Consumer<JsonNode> listener : notificationListeners) {   // 分发给所有通知监听器
                listener.accept(message);
            }
            return;                                             // 通知处理完就返回
        }
        long id = idNode.asLong();                               // 有 id → 是"响应"，取出请求 id
        CompletableFuture<JsonNode> future = pending.remove(id); // 按 id 从挂起表里找到对应请求的 future（找不到说明已超时被移除）
        if (future == null) {                                    // 没有对应的挂起请求
            return;                                              // 忽略（迟到的响应 / 已超时）
        }
        JsonNode error = message.get("error");                   // 看响应里有没有 error 字段
        if (error != null && !error.isNull()) {                  // 有 error → 这是一个错误响应
            future.completeExceptionally(new JsonRpcException(   // 让 future 以异常结束（唤醒 request() 里 future.get() 抛异常）
                    error.path("code").asInt(-32603),            // 错误码（默认 -32603 = Internal error）
                    error.path("message").asText("JSON-RPC error")));   // 错误信息
            return;
        }
        future.complete(message.get("result"));                  // 正常响应：用 result 完成 future（唤醒 request() 返回结果）
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
    }
}
