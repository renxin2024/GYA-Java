package cn.renxinblog.c01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** GYA C01：普通聊天与提示词动作协议的 Java 21 等价实现。 */
public final class C01PromptAction {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");
    static final String QUESTION = "请查询北京现在的天气。如果没有外部数据，请不要猜测。";
    static final String ACTION_PROMPT = """
            你是动作请求生成器，不要直接回答用户问题。

            可用动作：
            - get_weather(city: string)：查询指定城市的天气。

            当用户的问题需要查询天气时，只输出一行 JSON，不要输出 Markdown、解释或代码围栏：
            {"name":"get_weather","arguments":{"city":"城市名"}}

            不得使用未列出的动作，不得添加未声明的参数。
            """.strip();
    static final Map<String, String> WEATHER = Map.of(
            "北京", "多云，25℃，东北风3级",
            "上海", "小雨，22℃，东南风2级");

    private C01PromptAction() {
    }

    static String apiUrl() {
        String value = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com")
                .replaceAll("/+$", "");
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value + "/chat/completions";
    }

    static ObjectNode message(String role, String content) {
        return JSON.createObjectNode().put("role", role).put("content", content);
    }

    static String requestText(ArrayNode messages) throws Exception {
        String apiKey = System.getenv().getOrDefault(
                "LLM_API_KEY", System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""));
        if (apiKey.isBlank()) {
            throw new IllegalStateException("请先设置 LLM_API_KEY 或 DEEPSEEK_API_KEY");
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        payload.set("messages", messages);
        payload.put("temperature", 0);
        payload.putObject("thinking").put("type", "disabled");

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        String content = JSON.readTree(response.body())
                .path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.isBlank()) {
            throw new IllegalStateException("模型没有返回文本内容");
        }
        return content;
    }

    static ActionRequest parseAction(String text) {
        final JsonNode data;
        try {
            data = JSON.readTree(text);
        } catch (Exception exception) {
            throw new IllegalArgumentException("模型没有返回合法的纯 JSON", exception);
        }
        if (!data.isObject() || !fieldNames(data).equals(Set.of("name", "arguments"))) {
            throw new IllegalArgumentException("动作请求必须且只能包含 name 和 arguments");
        }

        String name = data.path("name").asText("");
        if (!"get_weather".equals(name)) {
            throw new IllegalArgumentException("不允许执行未知动作：" + name);
        }
        JsonNode arguments = data.path("arguments");
        if (!arguments.isObject() || !fieldNames(arguments).equals(Set.of("city"))) {
            throw new IllegalArgumentException("get_weather 参数必须且只能包含 city");
        }
        String city = arguments.path("city").asText("").trim();
        if (city.isBlank()) {
            throw new IllegalArgumentException("city 必须是非空字符串");
        }
        return new ActionRequest(name, city);
    }

    static Set<String> fieldNames(JsonNode node) {
        Iterator<String> iterator = node.fieldNames();
        java.util.HashSet<String> names = new java.util.HashSet<>();
        iterator.forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    static String executeAction(ActionRequest action) {
        if (!"get_weather".equals(action.name())) {
            throw new IllegalArgumentException("不允许执行未知动作：" + action.name());
        }
        return WEATHER.getOrDefault(action.city(), "暂无" + action.city() + "的天气演示数据");
    }

    static void runChat() throws Exception {
        String answer = requestText(JSON.createArrayNode().add(message("user", QUESTION)));
        System.out.println("普通聊天输出：" + answer);
        System.out.println("Java 执行动作：否");
    }

    static void runPromptTool() throws Exception {
        ArrayNode messages = JSON.createArrayNode()
                .add(message("system", ACTION_PROMPT))
                .add(message("user", QUESTION));
        String actionText = requestText(messages);
        System.out.println("模型输出动作文本：" + actionText);
        ActionRequest action = parseAction(actionText);
        String arguments = JSON.createObjectNode().put("city", action.city()).toString();
        System.out.println("Java 执行动作：" + action.name() + "(" + arguments + ")");
        System.out.println("工具返回：" + executeAction(action));
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 2 && "--mode".equals(args[0]) ? args[1] : "all";
        if (!Set.of("chat", "prompt-tool", "all").contains(mode)) {
            throw new IllegalArgumentException("mode 必须是 chat、prompt-tool 或 all");
        }
        if (Set.of("chat", "all").contains(mode)) {
            runChat();
        }
        if ("all".equals(mode)) {
            System.out.println("\n--- 加入动作格式提示词后 ---");
        }
        if (Set.of("prompt-tool", "all").contains(mode)) {
            runPromptTool();
        }
    }

    record ActionRequest(String name, String city) {
    }
}
