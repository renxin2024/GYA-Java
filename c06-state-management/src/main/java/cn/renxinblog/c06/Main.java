package cn.renxinblog.c06;

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
 * C06 演示（Java 21 + Gradle）：状态管理——手写状态机版
 *
 * 对应 Python 版 state_machine.py：State（Map）+ Node（函数）+ Edge（路由表）。
 * 核心演示：图管流程（路由确定性代码），LLM 管内容（只在语义节点调用）。
 *
 * 说明：LangGraph 是 Python 生态库，Java 版无等价官方实现，
 * 本文件实现手写状态机等价版本；StateGraph 的声明式图思想
 * 在 Java 里可以用 Map<nodeName, Function> 表达（见 Node 注册表）。
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   ./gradlew :c06-state-management:run
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
    // State：共享状态（Java 版用 Map）
    // ---------------------------------------------------------------
    static final Map<String, Object> STATE = new LinkedHashMap<>();

    // ---------------------------------------------------------------
    // Node 注册表：nodeName -> 函数（这就是 Java 版的"图声明"）
    // ---------------------------------------------------------------
    static final Map<String, Function<Map<String, Object>, Void>> NODES = new LinkedHashMap<>();

    // 工具
    static String getWeather(String city) {
        return switch (city) {
            case "北京" -> "多云，25℃，东北风 3 级";
            case "上海" -> "阵雨，28℃，东南风 2 级";
            case "深圳" -> "晴，31℃，南风 2 级";
            default -> "暂无 " + city + " 的天气数据";
        };
    }

    static String calculator(String expr) {
        if (!expr.matches("[0-9+\\-*/(). ]+")) throw new IllegalArgumentException("表达式包含非法字符");
        return String.valueOf(evalSimple(expr));
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

    static ArrayNode toolsJson() throws Exception {
        return (ArrayNode) JSON.readTree("""
                [{"type":"function","function":{"name":"get_weather",
                  "description":"查询指定城市的当前天气。城市例：北京、上海、深圳",
                  "parameters":{"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}}},
                 {"type":"function","function":{"name":"calculator",
                  "description":"执行算术表达式计算。例如：123*456、 (1+2)*3",
                  "parameters":{"type":"object","properties":{"expression":{"type":"string","description":"算术表达式"}},"required":["expression"]}}}]""");
    }

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

    @SuppressWarnings("unchecked")
    static List<ObjectNode> messages() {
        return (List<ObjectNode>) STATE.get("messages");
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> results() {
        return (Map<String, String>) STATE.get("results");
    }

    // ---- Node 1: 语义理解（LLM 节点）----
    static Void parseIntent(Map<String, Object> state) throws Exception {
        String sys = "你是意图解析器。判断用户问题需要哪些工具，只输出 JSON 列表，如 [\"get_weather\", \"calculator\"]，不需要其他内容。";
        ObjectNode sysMsg = JSON.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", sys);
        ObjectNode userMsg = JSON.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", String.valueOf(state.get("question")));
        JsonNode msg = callLLM(List.of(sysMsg, userMsg), null);

        List<String> intent = new ArrayList<>();
        String raw = msg.path("content").asText("[]").trim();
        try {
            for (JsonNode n : JSON.readTree(raw)) intent.add(n.asText());
        } catch (Exception ignored) { }
        state.put("intent", intent);
        state.put("next_step", "execute_tools");
        System.out.println("[Node: parse_intent] intent=" + intent);
        return null;
    }

    // ---- Node 2: 执行工具（路由确定性 + 工具参数 LLM 决策）----
    static Void executeTools(Map<String, Object> state) throws Exception {
        List<ObjectNode> msgs = messages();
        @SuppressWarnings("unchecked")
        List<String> intent = (List<String>) state.get("intent");
        if (!intent.isEmpty()) {
            JsonNode msg = callLLM(msgs, toolsJson());
            msgs.add((ObjectNode) msg);
            for (JsonNode tc : msg.path("tool_calls")) {
                String name = tc.path("function").path("name").asText();
                String argsStr = tc.path("function").path("arguments").asText("{}");
                Map<String, Object> args = JSON.readValue(argsStr, Map.class);
                String result;
                if (name.equals("get_weather")) result = getWeather(String.valueOf(args.get("city")));
                else if (name.equals("calculator")) result = calculator(String.valueOf(args.get("expression")));
                else result = "未知工具: " + name;
                results().put(name, result);
                ObjectNode toolMsg = JSON.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.path("id").asText());
                toolMsg.put("content", result);
                msgs.add(toolMsg);
                System.out.println("[Node: execute_tools] " + name + " → " + result);
            }
        }
        state.put("next_step", "summarize");
        return null;
    }

    // ---- Node 3: 总结（LLM 节点）----
    static Void summarize(Map<String, Object> state) throws Exception {
        JsonNode msg = callLLM(messages(), null);
        state.put("final_answer", msg.path("content").asText());
        state.put("next_step", "END");
        System.out.println("[Node: summarize] " + msg.path("content").asText("").substring(0, Math.min(80, msg.path("content").asText("").length())) + "...");
        return null;
    }

    // ---------------------------------------------------------------
    // 状态机引擎：while + next_step 路由（与 Python 版完全同构）
    // ---------------------------------------------------------------
    static Map<String, Object> run(int maxSteps) throws Exception {
        int steps = 0;
        while (!"END".equals(STATE.get("next_step")) && steps < maxSteps) {
            String node = (String) STATE.get("next_step");
            System.out.println("\n=== Node: " + node + " ===");
            NODES.get(node).apply(STATE);
            steps++;
        }
        return STATE;
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.isEmpty()) {
            System.out.println("请先设置 DEEPSEEK_API_KEY 环境变量（https://platform.deepseek.com 获取）");
            return;
        }

        String q = "北京天气怎么样？顺便算一下 123*456，最后把两个答案整理成一句话。";

        STATE.put("question", q);
        STATE.put("messages", new ArrayList<ObjectNode>());
        ((List<ObjectNode>) STATE.get("messages")).add(userMsg(q));
        STATE.put("results", new LinkedHashMap<String, String>());
        STATE.put("intent", new ArrayList<String>());
        STATE.put("next_step", "parse_intent");
        STATE.put("final_answer", "");

        // 注册节点（Java 版的"图声明"）
        NODES.put("parse_intent", state -> { try { return parseIntent(state); } catch (Exception e) { throw new RuntimeException(e); } });
        NODES.put("execute_tools", state -> { try { return executeTools(state); } catch (Exception e) { throw new RuntimeException(e); } });
        NODES.put("summarize", state -> { try { return summarize(state); } catch (Exception e) { throw new RuntimeException(e); } });

        Map<String, Object> result = run(10);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("最终答案: " + result.get("final_answer"));
        System.out.println("执行轨迹: intent=" + result.get("intent") + " results=" + result.get("results"));
    }

    static ObjectNode userMsg(String content) {
        ObjectNode n = JSON.createObjectNode();
        n.put("role", "user");
        n.put("content", content);
        return n;
    }
}
