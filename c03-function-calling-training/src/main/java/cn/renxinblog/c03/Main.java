package cn.renxinblog.c03;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C03 演示（Java 21 + Gradle）：模型"会调用工具"的能力是从哪来的？
 *
 * 同一个问题（北京和上海天气），三种问法对比：
 *   A) 裸问（无 tools）        → 模型文本回答，无 tool_calls
 *   B) 带 tools 参数           → 结构化 tool_calls 数组，各带独立 id
 *   C) 纯 prompt 手写格式      → 靠 system prompt 要求 JSON，多次运行会漏调用
 *
 * 与 Python 版 train_compare.py 完全同构。
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   ./gradlew :c03-function-calling-training:run
 *
 * 依赖：JDK 21 + Jackson（build.gradle.kts 声明，Gradle 自动拉取）。
 */
public class Main {

    static final String API_URL = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com/chat/completions");
    static final String API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    // 方式 C 用的 system prompt：把工具定义"翻译"成文本，要求模型输出 JSON
    static final String PROMPT_WITH_TOOLS = """
            你有以下工具可用：
            - get_weather(city): 查询指定城市的当前天气。城市例：北京、上海、深圳

            需要使用时，按以下 JSON 格式输出（不要输出其他任何内容）：
            {"name": "<工具名>", "arguments": {"city": "<城市名>"}}

            不需要使用时，直接回答用户问题。""";

    static final String QUESTION = "北京和上海现在天气分别怎么样？";
    static final int CITIES = 2;

    static JsonNode call(List<JsonNode> messages, JsonNode tools) throws Exception {
        var payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        payload.set("messages", JSON.valueToTree(messages));
        if (tools != null) payload.set("tools", tools);
        payload.put("stream", false);

        var req = HttpRequest.newBuilder(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        var resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        var root = JSON.readTree(resp.body());
        return root.path("choices").get(0).path("message");
    }

    /** 纯 prompt 模式的调用方解析：逐行/正则"捞" JSON 工具调用。 */
    static List<String> extractToolCallsManually(String text) {
        List<String> results = new ArrayList<>();
        Pattern nameArgs = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\".*?\"arguments\"\\s*:\\s*\\{(.*?)\\}");
        Matcher m = nameArgs.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String args = "{" + m.group(2) + "}";
            String key = name + args;
            if (!results.contains(key)) results.add(key);
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.isEmpty()) {
            System.out.println("请先设置 DEEPSEEK_API_KEY 环境变量（https://platform.deepseek.com 获取）");
            return;
        }

        System.out.println("模型: " + MODEL);
        System.out.println("问题: " + QUESTION);
        System.out.println("=".repeat(60));

        // ---------- A) 裸问：无 tools ----------
        System.out.println("\n[A] 不带 tools 参数（模型只会文本补全）");
        System.out.println("-".repeat(60));
        var msgA = call(List.of(userMsg(QUESTION)), null);
        System.out.println("content: " + truncate(msgA.path("content").asText("")));
        System.out.println("tool_calls: " + msgA.path("tool_calls"));
        if (msgA.path("tool_calls").isMissingNode() || msgA.path("tool_calls").isEmpty()) {
            System.out.println(">>> 没有 tool_calls 字段 → 模型不知道『可以用』工具，只会说话");
        }

        // ---------- B) 带 tools 参数 ----------
        System.out.println("\n[B] 带 tools 参数（API 层注入 + 后训练格式约定）");
        System.out.println("-".repeat(60));
        var msgB = call(List.of(userMsg(QUESTION)), toolsJson());
        var tcs = msgB.path("tool_calls");
        System.out.println("content: " + truncate(msgB.path("content").asText("")));
        System.out.println("tool_calls: " + tcs.toString());
        System.out.println(">>> 模型输出 " + tcs.size() + " 个结构化对象，每个带独立 id，调用方可直接执行");

        // ---------- C) 纯 prompt 手写格式（无 tools 参数） ----------
        System.out.println("\n[C] 不带 tools 参数，只靠 system prompt 要求输出 JSON（跑 5 次）");
        System.out.println("-".repeat(60));
        int cOk = 0;
        for (int i = 1; i <= 5; i++) {
            var msgC = call(List.of(systemMsg(PROMPT_WITH_TOOLS), userMsg(QUESTION)), null);
            String raw = msgC.path("content").asText("");
            int got = extractToolCallsManually(raw).size();
            String status = got == CITIES ? "✓ 数量正好" : (got < CITIES ? "✗ 漏了 " + (CITIES - got) + " 个" : "✗ 多了 " + (got - CITIES) + " 个");
            System.out.println("第" + i + "次: 解析到 " + got + " 个工具调用  " + status);
            if (got == CITIES) cOk++;
        }
        System.out.println(">>> 方式 C 5 次里 " + cOk + " 次能解析出恰好 2 个工具调用（其余会漏）");

        // ---------- D) 稳定性统计 ----------
        System.out.println("\n[D] 稳定性对比：方式 B vs 方式 C 各跑 5 次，统计输出完整性");
        System.out.println("-".repeat(60));
        int okB = 0, okC = 0;
        for (int i = 0; i < 5; i++) {
            var mb = call(List.of(userMsg(QUESTION)), toolsJson());
            if (mb.path("tool_calls").size() == CITIES) okB++;
            var mc = call(List.of(systemMsg(PROMPT_WITH_TOOLS), userMsg(QUESTION)), null);
            if (extractToolCallsManually(mc.path("content").asText("")).size() == CITIES) okC++;
        }
        System.out.println("方式 B（带 tools 参数）: " + okB + "/5 次输出完整的 " + CITIES + " 个 tool_calls");
        System.out.println("方式 C（纯 prompt 手写）: " + okC + "/5 次能被解析器完整捞到 " + CITIES + " 个调用");
        System.out.println("\n提示：B 的稳定性来自 API 层 + 后训练；C 的成败取决于模型的『自觉』和你的解析器运气。");
    }

    static JsonNode userMsg(String content) {
        var n = JSON.createObjectNode();
        n.put("role", "user");
        n.put("content", content);
        return n;
    }

    static JsonNode systemMsg(String content) {
        var n = JSON.createObjectNode();
        n.put("role", "system");
        n.put("content", content);
        return n;
    }

    static JsonNode toolsJson() throws Exception {
        return JSON.readTree("""
                [{
                  "type": "function",
                  "function": {
                    "name": "get_weather",
                    "description": "查询指定城市的当前天气。城市例：北京、上海、深圳",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "city": {"type": "string", "description": "城市名"}
                      },
                      "required": ["city"]
                    }
                  }
                }]""");
    }

    static String truncate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
