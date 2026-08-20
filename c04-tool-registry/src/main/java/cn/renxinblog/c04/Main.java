package cn.renxinblog.c04;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * C04 演示（Java 21 + Gradle）：工具注册表与 ToolResponse 协议
 *
 * 与 Python 版 registry_demo.py 完全同构：
 *   [1] 正常闭环：模型发现并调用 get_weather + calculator
 *   [2] UNKNOWN_TOOL：调用不存在的工具 → 显式错误码
 *   [3] INVALID_PARAM：参数不对 → 显式错误码
 *   [4] 下线：unregister 后模型不再调用该工具
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   ./gradlew :c04-tool-registry:run
 *
 * 依赖：JDK 21 + Jackson（build.gradle.kts 声明，Gradle 自动拉取）。
 */
public class Main {

    static final String API_URL = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com/chat/completions");
    static final String API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    // ---------------------------------------------------------------
    // 1. ToolResponse：工具返回的结构化协议
    // ---------------------------------------------------------------
    record ToolResponse(String status, String text, Map<String, Object> data, Map<String, Object> errorInfo) {
        static ToolResponse ok(String text) {
            return new ToolResponse("SUCCESS", text, Map.of("result", text), null);
        }

        static ToolResponse err(String code, String text) {
            return new ToolResponse("ERROR", text, Map.of(), Map.of("code", code));
        }
    }

    // ---------------------------------------------------------------
    // 2. ToolRegistry：注册表（register / unregister / execute / listSchemas）
    // ---------------------------------------------------------------
    static class ToolRegistry {
        record Tool(Function<Map<String, Object>, ToolResponse> fn, String description, ObjectNode parameters) {}

        final Map<String, Tool> tools = new LinkedHashMap<>();

        void register(String name, String description, ObjectNode parameters,
                      Function<Map<String, Object>, ToolResponse> fn) {
            tools.put(name, new Tool(fn, description, parameters));
        }

        void unregister(String name) {
            tools.remove(name);
        }

        ArrayNode listSchemas() {
            ArrayNode arr = JSON.createArrayNode();
            tools.forEach((name, tool) -> {
                ObjectNode schema = JSON.createObjectNode();
                schema.put("type", "function");
                ObjectNode fn = schema.putObject("function");
                fn.put("name", name);
                fn.put("description", tool.description());
                fn.set("parameters", tool.parameters());
                arr.add(schema);
            });
            return arr;
        }

        ToolResponse execute(String name, Map<String, Object> args) {
            Tool tool = tools.get(name);
            if (tool == null) {
                return ToolResponse.err("UNKNOWN_TOOL",
                        "错误：工具 " + name + " 不存在。可用工具: " + String.join(", ", tools.keySet()));
            }
            try {
                return tool.fn().apply(args);
            } catch (Exception e) {
                return ToolResponse.err("EXECUTION_ERROR", "错误：执行失败。 " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // 3. 工具实现
    // ---------------------------------------------------------------
    static final Map<String, String> FAKE_WEATHER = Map.of(
            "北京", "多云，25℃，东北风 3 级",
            "上海", "阵雨，28℃，东南风 2 级",
            "深圳", "晴，31℃，南风 2 级");

    static ToolResponse getWeather(Map<String, Object> args) {
        String city = String.valueOf(args.get("city"));
        if (!FAKE_WEATHER.containsKey(city)) {
            throw new IllegalArgumentException("暂无 " + city + " 的天气数据");
        }
        return ToolResponse.ok(city + ": " + FAKE_WEATHER.get(city));
    }

    static ToolResponse calculator(Map<String, Object> args) {
        String expr = String.valueOf(args.get("expression"));
        if (!expr.matches("[0-9+\\-*/(). ]+")) {
            throw new IllegalArgumentException("表达式包含非法字符");
        }
        // 演示用简化计算器（仅支持 + - * / 整数）。生产请用真正的表达式求值库。
        double result = evalSimple(expr);
        return ToolResponse.ok(String.valueOf(result));
    }

    static ToolResponse searchNotes(Map<String, Object> args) {
        String query = String.valueOf(args.get("query"));
        return ToolResponse.ok("找到与『" + query + "』相关的笔记 3 条（演示数据）");
    }

    /** 极简四则运算求值（演示用）：解析形如 123*456 的表达式。 */
    static double evalSimple(String expr) {
        String t = expr.replaceAll("\\s", "");
        String[] mul = t.split("\\*");
        if (mul.length == 2) {
            return Double.parseDouble(mul[0]) * Double.parseDouble(mul[1]);
        }
        String[] add = t.split("\\+");
        if (add.length == 2) {
            return Double.parseDouble(add[0]) + Double.parseDouble(add[1]);
        }
        String[] sub = t.split("-", 2);
        if (sub.length == 2) {
            return Double.parseDouble(sub[0]) - Double.parseDouble(sub[1]);
        }
        String[] div = t.split("/");
        if (div.length == 2) {
            return Double.parseDouble(div[0]) / Double.parseDouble(div[1]);
        }
        return Double.parseDouble(t);
    }

    static ObjectNode paramSchema(String... props) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (String p : props) {
            properties.putObject(p).put("type", "string");
            required.add(p);
        }
        return schema;
    }

    // ---------------------------------------------------------------
    // 4. 与模型交互
    // ---------------------------------------------------------------
    static JsonNode call(List<ObjectNode> messages, JsonNode tools) throws Exception {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        ArrayNode arr = payload.putArray("messages");
        messages.forEach(arr::add);
        if (tools != null) payload.set("tools", tools);
        payload.put("stream", false);

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(resp.body());
        if (!root.has("choices")) {
            throw new RuntimeException("API 错误: " + resp.body());
        }
        return root.path("choices").get(0).path("message");
    }

    static void runAgentRound(ToolRegistry reg, String question) throws Exception {
        List<ObjectNode> messages = new ArrayList<>();
        ObjectNode user = JSON.createObjectNode();
        user.put("role", "user");
        user.put("content", question);
        messages.add(user);

        JsonNode msg = call(messages, reg.listSchemas());
        System.out.println("问题: " + question);

        JsonNode toolCalls = msg.path("tool_calls");
        if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
            System.out.println("[模型直接回答] " + msg.path("content").asText());
            System.out.println();
            return;
        }

        // assistant 消息原样回传（含 reasoning_content，DeepSeek thinking 模式要求）
        messages.add((ObjectNode) msg);
        for (JsonNode tc : toolCalls) {
            String fnName = tc.path("function").path("name").asText();
            String argsStr = tc.path("function").path("arguments").asText("");
            System.out.println("  [模型说] 调用 " + fnName + "(" + argsStr + ")");
            Map<String, Object> args = JSON.readValue(argsStr, Map.class);
            ToolResponse resp = reg.execute(fnName, args);
            System.out.println("  [注册表] status=" + resp.status() + ", text=" + resp.text());
            if (resp.errorInfo() != null) {
                System.out.println("  [注册表] error_code=" + resp.errorInfo().get("code"));
            }
            ObjectNode toolMsg = JSON.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tc.path("id").asText());
            toolMsg.put("content", resp.text());
            messages.add(toolMsg);
        }

        JsonNode r2 = call(messages, null);
        System.out.println("[模型总结] " + r2.path("content").asText());
        System.out.println();
    }

    // ---------------------------------------------------------------
    // 5. main
    // ---------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        if (API_KEY.isEmpty()) {
            System.out.println("请先设置 DEEPSEEK_API_KEY 环境变量（https://platform.deepseek.com 获取）");
            return;
        }

        ToolRegistry reg = new ToolRegistry();
        reg.register("get_weather", "查询指定城市的当前天气。城市例：北京、上海、深圳",
                paramSchema("city"), Main::getWeather);
        reg.register("calculator", "执行算术表达式计算。例如：123*456、 (1+2)*3",
                paramSchema("expression"), Main::calculator);
        reg.register("search_notes", "在个人知识库中搜索笔记。",
                paramSchema("query"), Main::searchNotes);

        System.out.println("模型: " + MODEL);
        System.out.println("已注册工具: " + reg.tools.keySet());
        System.out.println("=".repeat(60));

        System.out.println("\n[1] 正常闭环：模型从 schema 发现工具并调用");
        runAgentRound(reg, "北京现在天气怎么样？顺便算一下 123*456");

        System.out.println("[2] 错误处理：直接调用不存在的工具（模拟模型幻觉）");
        ToolResponse resp2 = reg.execute("send_email", Map.of("to", "a@b.com"));
        System.out.println("status=" + resp2.status() + ", error_code=" + resp2.errorInfo().get("code"));
        System.out.println("text=" + resp2.text() + "\n");

        System.out.println("[3] 错误处理：参数不对（calculator 缺 expression）");
        ToolResponse resp3 = reg.execute("calculator", Map.of());
        System.out.println("status=" + resp3.status() + ", error_code=" + resp3.errorInfo().get("code"));
        System.out.println("text=" + resp3.text() + "\n");

        System.out.println("[4] 下线工具：unregister('get_weather')");
        reg.unregister("get_weather");
        System.out.println("剩余工具: " + reg.tools.keySet());
        System.out.println("现在问天气，模型会怎么做？");
        runAgentRound(reg, "北京现在天气怎么样？");
    }
}
