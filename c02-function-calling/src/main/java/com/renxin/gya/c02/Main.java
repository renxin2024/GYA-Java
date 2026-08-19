package com.renxin.gya.c02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * C02 演示（Java 21 + Gradle）：Function Calling 第一性原理
 *
 * 模型并没有"学会"调用工具——它只是输出了一个 JSON 格式的工具调用请求，
 * 真正执行函数的是调用方（也就是本程序）的代码。
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   gradle run
 *
 * 依赖：JDK 21 + Jackson（build.gradle.kts 声明，Gradle 自动拉取）。
 * 与 Python 版 main.py 完全同构：模型只说、代码做。
 */
public class Main {

    static final String API_URL = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com/chat/completions");
    static final String API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");

    static final ObjectMapper JSON = new ObjectMapper();

    // ---------------------------------------------------------------
    // 1. 声明模型"可以用"哪些工具。（声明 ≠ 执行）
    // ---------------------------------------------------------------
    static final String TOOLS_JSON = """
            [{
              "type": "function",
              "function": {
                "name": "get_weather",
                "description": "查询指定城市的当前天气。城市例：北京、上海、深圳",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "city": { "type": "string", "description": "城市名" }
                  },
                  "required": ["city"]
                }
              }
            }]
            """.strip();

    // 模拟的工具返回数据（真实系统里这里会去请求天气 API）
    static final Map<String, String> FAKE_WEATHER = Map.of(
            "北京", "多云，25℃，东北风 3 级",
            "上海", "阵雨，28℃，东南风 2 级",
            "深圳", "晴，31℃，南风 2 级");

    /** 真正执行工具的函数——它才是"会干活"的那一方。模型只是说"我想查"，
     *  这个函数真的去查（这里简化成查一张本地表）。 */
    static String getWeather(String city) {
        return FAKE_WEATHER.getOrDefault(city, "暂无 " + city + " 的天气数据");
    }

    /** 发送一次 chat/completions 请求，返回解析后的 JSON。 */
    static JsonNode call(String messagesJson, String toolsJson) throws Exception {
        var payloadNode = JSON.createObjectNode();
        payloadNode.put("model", MODEL);
        payloadNode.set("messages", JSON.readTree(messagesJson));
        if (toolsJson != null) {
            payloadNode.set("tools", JSON.readTree(toolsJson));
        }
        payloadNode.put("stream", false);
        String payload = payloadNode.toString();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(req,
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.isEmpty()) {
            System.out.println("请先设置 DEEPSEEK_API_KEY 环境变量（https://platform.deepseek.com 获取）");
            System.exit(1);
        }

        String question = "北京现在天气怎么样？";
        System.out.printf("模型: %s%n问题: %s%n%s%n", MODEL, question, "-".repeat(46));

        // 第一轮：把问题 + 工具声明一起发给模型
        String messages = """
                [{"role": "user", "content": "%s"}]
                """.formatted(question);
        JsonNode r = call(messages, TOOLS_JSON);
        JsonNode msg = r.path("choices").path(0).path("message");

        if (msg.has("tool_calls")) {
            // 模型说："我想调用 get_weather(北京)" —— 注意它只说了，没做
            JsonNode tc = msg.path("tool_calls").path(0);
            String toolCallId = tc.path("id").asText("call_demo_001");
            String fnName = tc.path("function").path("name").asText();
            JsonNode toolArgs = JSON.readTree(tc.path("function").path("arguments").asText());
            String city = toolArgs.path("city").asText("");
            System.out.println("[模型说] 我要调用工具: " + fnName + "({\"city\": \"" + city + "\"})");

            // 现在调用方（我们的代码）真正执行
            String result = getWeather(city);
            System.out.println("[调用方] 我已执行工具，结果是: " + result);

            // 回喂：按 API 语义带上 assistant 的 tool_calls 记录，再接 role=tool 工具结果。
            // 用 Jackson 构造消息数组，避免手拼字符串导致嵌套 JSON 转义错误。
            var userMsg = JSON.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", question);

            var assistantMsg = JSON.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.putNull("content");
            var tcArr = JSON.createArrayNode();
            var tcNode = JSON.createObjectNode();
            tcNode.put("id", toolCallId);
            tcNode.put("type", "function");
            var fnNode = tcNode.putObject("function");
            fnNode.put("name", fnName);
            fnNode.put("arguments", tc.path("function").path("arguments").asText());
            tcArr.add(tcNode);
            assistantMsg.set("tool_calls", tcArr);

            var toolMsg = JSON.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("content", result);

            var messages2Arr = JSON.createArrayNode();
            messages2Arr.add(userMsg);
            messages2Arr.add(assistantMsg);
            messages2Arr.add(toolMsg);

            // 第二轮：模型拿到工具结果，生成最终回答
            JsonNode r2 = call(messages2Arr.toString(), null);
            String content = r2.path("choices").path(0).path("message").path("content").asText();
            System.out.println("[模型最后说] " + content);
        } else {
            String content = msg.path("content").asText();
            System.out.println("[模型直接回答] " + content);
        }
    }
}