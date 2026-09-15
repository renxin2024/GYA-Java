package cn.renxinblog.c05;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * C05 演示（Java 21 + Gradle）：手写 ReAct Agent——循环的诞生
 *
 * 与 Python 版四文件等价（单文件组织，对应 react_loop.py + tools.py + state.py）：
 *   while 未终止:
 *       LLM(问题 + 历史) → Thought + Action
 *       执行 Action → Observation
 *       追加到历史，继续
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   ./gradlew :c05-react-agent:run
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
    // 工具系统（简化版 ToolRegistry）
    // ---------------------------------------------------------------
    record Tool(String description, ObjectNode parameters, java.util.function.Function<Map<String, Object>, String> fn) {}

    static final Map<String, Tool> REGISTRY = new java.util.LinkedHashMap<>();

    static void register(String name, String description, ObjectNode parameters,
                         java.util.function.Function<Map<String, Object>, String> fn) {
        REGISTRY.put(name, new Tool(description, parameters, fn));
    }

    static String getWeather(Map<String, Object> args) {
        String city = String.valueOf(args.get("city"));
        return switch (city) {
            case "北京" -> "北京: 多云，25℃，东北风 3 级";
            case "上海" -> "上海: 阵雨，28℃，东南风 2 级";
            case "深圳" -> "深圳: 晴，31℃，南风 2 级";
            default -> throw new IllegalArgumentException("暂无 " + city + " 的天气数据");
        };
    }

    static String calculator(Map<String, Object> args) {
        String expr = String.valueOf(args.get("expression"));
        if (!expr.matches("[0-9+\\-*/(). ]+")) throw new IllegalArgumentException("表达式包含非法字符");
        return String.valueOf(evalSimple(expr));
    }

    static String searchNotes(Map<String, Object> args) {
        return "找到与『" + args.get("query") + "』相关的笔记 3 条（演示数据）";
    }

    static double evalSimple(String expr) {
        String t = expr.replaceAll("\\s", "");
        String[] mul = t.split("\\*");
        if (mul.length == 2) return Double.parseDouble(mul[0]) * Double.parseDouble(mul[1]);
        String[] add = t.split("\\+");
        if (add.length == 2) return Double.parseDouble(add[0]) + Double.parseDouble(add[1]);
        String[] sub = t.split("-", 2);
        if (sub.length == 2) return Double.parseDouble(sub[0]) - Double.parseDouble(sub[1]);
        String[] div = t.split("/");
        if (div.length == 2) return Double.parseDouble(div[0]) / Double.parseDouble(div[1]);
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

    static ArrayNode listSchemas() {
        ArrayNode arr = JSON.createArrayNode();
        REGISTRY.forEach((name, tool) -> {
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

    // ---------------------------------------------------------------
    // LLM 调用
    // ---------------------------------------------------------------
    static JsonNode callLLM(List<ObjectNode> messages, JsonNode tools) throws Exception {
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
        if (!root.has("choices")) throw new RuntimeException("API 错误: " + resp.body());
        return root.path("choices").get(0).path("message");
    }

    // ---------------------------------------------------------------
    // ReAct 主循环
    // ---------------------------------------------------------------
    static String runAgent(String question, int maxSteps) throws Exception {
        List<ObjectNode> messages = new ArrayList<>();
        ObjectNode user = JSON.createObjectNode();
        user.put("role", "user");
        user.put("content", question);
        messages.add(user);

        System.out.println("问题: " + question);
        for (int step = 1; step <= maxSteps; step++) {
            System.out.println("\n=== Step " + step + " ===");
            JsonNode msg = callLLM(messages, listSchemas());
            String thought = msg.path("reasoning_content").asText("");
            JsonNode toolCalls = msg.path("tool_calls");

            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
                String answer = msg.path("content").asText("");
                System.out.println("[Thought] " + truncate(thought, 120));
                System.out.println("[Final Answer] " + answer);
                return answer;
            }

            // assistant 消息原样回传（含 reasoning_content）
            messages.add((ObjectNode) msg);
            for (JsonNode tc : toolCalls) {
                String name = tc.path("function").path("name").asText();
                String argsStr = tc.path("function").path("arguments").asText("{}");
                System.out.println("[Thought] " + truncate(thought, 120));
                System.out.println("[Action] 调用 " + name + "(" + argsStr + ")");
                Map<String, Object> args = JSON.readValue(argsStr, Map.class);
                try {
                    String result = REGISTRY.get(name).fn().apply(args);
                    System.out.println("[Observation] " + truncate(result, 100));
                    ObjectNode toolMsg = JSON.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tc.path("id").asText());
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                } catch (Exception e) {
                    String err = "错误：" + e.getMessage();
                    System.out.println("[Observation] " + err);
                    ObjectNode toolMsg = JSON.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tc.path("id").asText());
                    toolMsg.put("content", err);
                    messages.add(toolMsg);
                }
            }
        }
        return "（达到最大步数仍未完成，已终止）";
    }

    static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ---------------------------------------------------------------
    // main
    // ---------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        if (API_KEY.isEmpty()) {
            System.out.println("请先设置 DEEPSEEK_API_KEY 环境变量（https://platform.deepseek.com 获取）");
            return;
        }

        register("get_weather", "查询指定城市的当前天气。城市例：北京、上海、深圳",
                paramSchema("city"), Main::getWeather);
        register("calculator", "执行算术表达式计算。例如：123*456、 (1+2)*3",
                paramSchema("expression"), Main::calculator);
        register("search_notes", "在个人知识库中搜索笔记。",
                paramSchema("query"), Main::searchNotes);

        System.out.println("模型: " + MODEL);
        System.out.println("=".repeat(60));

        String question = args.length > 0 ? String.join(" ", args)
                : "北京天气怎么样？顺便算一下 123*456，最后把两个答案整理成一句话。";
        String answer = runAgent(question, 6);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("最终答案:");
        System.out.println(answer);
    }
}
