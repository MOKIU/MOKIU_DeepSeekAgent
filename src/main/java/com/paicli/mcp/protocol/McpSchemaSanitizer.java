package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * MCP 工具参数 schema 的清洗器。
 *
 * 背景：外部 MCP server 返回的 inputSchema 经常带大量"对模型不友好"的噪音，
 * 直接塞进 prompt 会提高模型调用失败率、浪费 token。所以注册工具前先清洗：
 *   1. 删 $ref / $schema / $id —— 模型不理解 JSON Schema 的引用机制；
 *   2. anyOf / oneOf 转成 description 文本 —— 模型不擅长处理多选结构；
 *   3. description 截断（默认 1000 字符）—— 超长描述浪费 token；
 *   4. 保证有 type 和 properties 兜底 —— 避免空 schema 让模型无从下手。
 */
public final class McpSchemaSanitizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DESCRIPTION_CHARS = 1000;   // description 最大保留字符数

    private McpSchemaSanitizer() {
    }

    /**
     * 清洗入口：返回一个"模型友好"的参数 schema。
     * 对空 schema、非对象 schema 做兜底，保证返回的永远是合法对象结构。
     */
    public static JsonNode sanitize(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {   // 空 schema
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");        // 兜底成空对象
            fallback.putObject("properties");
            return fallback;
        }
        JsonNode copy = schema.deepCopy();          // 深拷贝，避免修改原始 schema
        JsonNode cleaned = clean(copy);             // 递归清洗（删 $ref、转 anyOf、截断 description）
        if (!cleaned.isObject()) {                  // 清洗后不是对象（比如直接是个字符串/数组）
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.set("description", cleaned);   // 把原内容塞进 description
            return fallback;
        }
        ObjectNode obj = (ObjectNode) cleaned;
        if (!obj.has("type")) {                     // 缺 type 就补默认 object
            obj.put("type", "object");
        }
        if (!obj.has("properties")) {               // 缺 properties 就补空对象
            obj.putObject("properties");
        }
        return obj;
    }

    /**
     * 递归清洗：删引用关键字、把 anyOf/oneOf 转成文本、截断 description。
     */
    private static JsonNode clean(JsonNode node) {
        if (node == null || node.isNull()) {        // null 节点直接返回
            return node;
        }
        if (node.isObject()) {                      // 对象节点
            ObjectNode object = (ObjectNode) node;
            object.remove("$schema");               // 删 $schema（模型不懂 schema 元信息）
            object.remove("$id");                   // 删 $id
            object.remove("$ref");                  // 删 $ref（模型不懂引用，会困惑）

            StringBuilder alternatives = new StringBuilder();   // 累积 anyOf/oneOf 的选项描述
            for (String keyword : new String[]{"anyOf", "oneOf"}) {
                JsonNode union = object.remove(keyword);        // 移除 anyOf/oneOf 节点
                if (union != null && union.isArray()) {         // 有且是数组
                    if (!alternatives.isEmpty()) {
                        alternatives.append("; ");
                    }
                    alternatives.append(keyword).append(" options: ");   // 如 "anyOf options: "
                    for (int i = 0; i < union.size(); i++) {     // 遍历每个选项
                        if (i > 0) alternatives.append(", ");
                        JsonNode option = union.get(i);
                        alternatives.append(option.path("type").asText(option.toString()));   // 取选项的 type 描述
                    }
                }
            }
            if (!alternatives.isEmpty()) {                      // 有 anyOf/oneOf 被转成文本
                object.put("type", "object");                   // 兜底成 object
                String existing = object.path("description").asText("");   // 取原有 description
                object.put("description", truncateDescription(   // 把"多选选项"拼进 description
                        existing.isBlank() ? alternatives.toString() : existing + " (" + alternatives + ")"));
            }

            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();   // 遍历对象的所有字段
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if ("description".equals(field.getKey()) && child.isTextual()) {   // 是 description 字段
                    object.put("description", truncateDescription(child.asText()));   // 截断超长描述
                } else {
                    clean(child);                           // 其它字段递归清洗
                }
            }
            return object;
        }
        if (node.isArray()) {                           // 数组节点
            ArrayNode array = (ArrayNode) node;
            for (JsonNode child : array) {
                clean(child);                           // 递归清洗每个元素
            }
        }
        return node;
    }

    /** description 截断：超过 1000 字符就截断并加省略号。 */
    private static String truncateDescription(String description) {
        if (description == null || description.length() <= MAX_DESCRIPTION_CHARS) {   // 没超长
            return description;
        }
        return description.substring(0, MAX_DESCRIPTION_CHARS) + "...";   // 截断 + 省略号
    }
}
