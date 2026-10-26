package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class McpInitializeRequest {
    public static final String PROTOCOL_VERSION = "2025-03-26";

    private McpInitializeRequest() {
    }
    // 构造 MCP（模型上下文协议）握手阶段发送给服务器的 initialize 请求参数（即 JSON-RPC 的 params 部分）
    public static ObjectNode toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode clientInfo = root.putObject("clientInfo");
        clientInfo.put("name", "paicli");
        clientInfo.put("version", "11.0.0");
        return root;
    }
}
