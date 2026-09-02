package cn.renxinblog.c02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GYA C02：Function Calling 的单轮闭环，Java 21 实现。 */
public final class C02Demo {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String API_URL = System.getenv().getOrDefault(
            "LLM_API_URL", "https://api.deepseek.com/chat/completions");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");
    static final String PROMPT = "请查询北京现在的天气；需要外部数据时请使用 get_weather 工具。";
    static final Map<String, String> FAKE_WEATHER = Map.of(
            "北京", "多云，25℃，东北风3级",
            "上海", "小雨，22℃，东南风2级");

    private C02Demo() {
    }

    static ArrayNode tools() {
        ObjectNode function = JSON.createObjectNode();
        function.put("name", "get_weather");
        function.put("description", "查询指定城市的天气演示数据");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters.putObject("properties").putObject("city")
                .put("type", "string")
                .put("description", "城市名，如北京");
        parameters.putArray("required").add("city");
        parameters.put("additionalProperties", false);
        ObjectNode tool = JSON.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return JSON.createArrayNode().add(tool);
    }

    static ObjectNode userMessage(String content) {
        return JSON.createObjectNode().put("role", "user").put("content", content);
    }

    static ObjectNode buildPayload(ArrayNode messages, boolean includeTools) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        payload.set("messages", messages);
        payload.put("stream", false);
        payload.putObject("thinking").put("type", "disabled");
        if (includeTools) {
            payload.set("tools", tools());
        }
        return payload;
    }

    static JsonNode callModel(ArrayNode messages, boolean includeTools) throws Exception {
        String apiKey = System.getenv().getOrDefault(
                "LLM_API_KEY", System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""));
        if (apiKey.isBlank()) {
            throw new ModelApiException(
                    "缺少 API Key：请设置 LLM_API_KEY 或 DEEPSEEK_API_KEY；dry-run 不需要 Key");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildPayload(messages, includeTools).toString()))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelApiException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    static JsonNode firstMessage(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            throw new ModelApiException("响应缺少 choices[0].message");
        }
        return message;
    }

    static ToolRequest validateToolCall(JsonNode toolCalls) throws Exception {
        if (!toolCalls.isArray() || toolCalls.size() != 1) {
            throw new ToolRequestException("C02 只允许一次请求包含一个 tool_call");
        }
        JsonNode toolCall = toolCalls.get(0);
        String id = toolCall.path("id").asText("");
        String name = toolCall.path("function").path("name").asText("");
        String argumentText = toolCall.path("function").path("arguments").asText("");
        JsonNode arguments;
        try {
            arguments = JSON.readTree(argumentText);
        } catch (Exception exception) {
            throw new ToolRequestException("tool_call arguments JSON 无效", exception);
        }
        if (id.isBlank()) {
            throw new ToolRequestException("tool_call 缺少 id");
        }
        if (!"get_weather".equals(name)) {
            throw new ToolRequestException("不允许执行未知工具: " + name);
        }
        if (!arguments.isObject() || arguments.size() != 1 || !arguments.has("city")) {
            throw new ToolRequestException("get_weather 参数必须且只能包含 city");
        }
        String city = arguments.path("city").asText("").trim();
        if (city.isBlank()) {
            throw new ToolRequestException("city 必须是非空字符串");
        }
        return new ToolRequest(id, name, city);
    }

    static String executeWeather(ToolRequest request) {
        if (!"get_weather".equals(request.name())) {
            throw new ToolRequestException("不允许执行未知工具: " + request.name());
        }
        return FAKE_WEATHER.getOrDefault(request.city(), "暂无" + request.city() + "的天气演示数据");
    }

    static ArrayNode followupMessages(
            String prompt, JsonNode assistantMessage, String toolCallId, String result) {
        ObjectNode assistant = JSON.createObjectNode();
        assistant.put("role", "assistant");
        if (assistantMessage.path("content").isNull()) {
            assistant.putNull("content");
        } else {
            assistant.put("content", assistantMessage.path("content").asText(""));
        }
        assistant.set("tool_calls", assistantMessage.path("tool_calls"));
        ObjectNode tool = JSON.createObjectNode()
                .put("role", "tool")
                .put("tool_call_id", toolCallId)
                .put("content", result);
        return JSON.createArrayNode().add(userMessage(prompt)).add(assistant).add(tool);
    }

    static ArrayNode brokenFollowupMessages(String prompt, String toolCallId, String result) {
        ObjectNode tool = JSON.createObjectNode()
                .put("role", "tool")
                .put("tool_call_id", toolCallId)
                .put("content", result);
        return JSON.createArrayNode().add(userMessage(prompt)).add(tool);
    }

    static void emit(String event, Object... fields) {
        ObjectNode line = JSON.createObjectNode().put("event", event);
        for (int index = 0; index < fields.length; index += 2) {
            line.set(fields[index].toString(), JSON.valueToTree(fields[index + 1]));
        }
        System.out.println(line);
    }

    static int dryRun() {
        ArrayNode messages = JSON.createArrayNode().add(userMessage(PROMPT));
        emit("request.prepared", "scenario", "without-tools", "payload", buildPayload(messages, false));
        emit("request.prepared", "scenario", "with-tools", "payload", buildPayload(messages, true));
        emit("run.finished", "status", "dry_run");
        return 0;
    }

    static int runReal(String scenario) throws Exception {
        ArrayNode messages = JSON.createArrayNode().add(userMessage(PROMPT));
        if ("without-tools".equals(scenario)) {
            emit("request.prepared", "scenario", scenario, "has_tools", false);
            JsonNode message = firstMessage(callModel(messages, false));
            emit("model.direct_answer", "content", message.path("content").asText(""));
            emit("run.finished", "status", "direct_answer");
            return 0;
        }

        emit("request.prepared", "scenario", scenario, "has_tools", true);
        if ("broken-protocol".equals(scenario)) {
            String toolCallId = "call_demo_missing_assistant";
            String result = FAKE_WEATHER.get("北京");
            ArrayNode broken = brokenFollowupMessages(PROMPT, toolCallId, result);
            emit("runtime.returned_tool_result", "protocol", "broken", "roles", roles(broken));
            try {
                callModel(broken, true);
            } catch (ModelApiException exception) {
                emit("protocol.rejected", "error", exception.getMessage());
                emit("run.finished", "status", "expected_failure");
                return 0;
            }
            emit("run.finished", "status", "unexpected_protocol_acceptance");
            return 4;
        }

        JsonNode assistantMessage = firstMessage(callModel(messages, true));
        JsonNode toolCalls = assistantMessage.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            emit("model.direct_answer", "content", assistantMessage.path("content").asText(""));
            emit("run.finished", "status", "model_skipped_tool");
            return 0;
        }

        ToolRequest request = validateToolCall(toolCalls);
        emit("model.requested_tool", "tool_call_id", request.id(), "name", request.name(),
                "arguments", Map.of("city", request.city()));
        if ("with-tools".equals(scenario)) {
            emit("run.finished", "status", "tool_not_executed");
            return 0;
        }

        String result = executeWeather(request);
        emit("runtime.executed_tool", "name", request.name(),
                "arguments", Map.of("city", request.city()), "result", result);
        ArrayNode followup = followupMessages(PROMPT, assistantMessage, request.id(), result);
        emit("runtime.returned_tool_result", "roles", roles(followup), "tool_call_id", request.id());
        JsonNode finalMessage = firstMessage(callModel(followup, true));
        if (finalMessage.path("tool_calls").isArray() && !finalMessage.path("tool_calls").isEmpty()) {
            emit("run.finished", "status", "unexpected_second_tool_call");
            return 3;
        }
        emit("model.final_answer", "content", finalMessage.path("content").asText(""));
        emit("runtime.direct_call_result", "name", request.name(),
                "arguments", Map.of("city", request.city()), "result", executeWeather(request));
        emit("run.finished", "status", "completed");
        return 0;
    }

    static List<String> roles(ArrayNode messages) {
        List<String> roles = new ArrayList<>();
        messages.forEach(node -> roles.add(node.path("role").asText()));
        return List.copyOf(roles);
    }

    public static void main(String[] args) {
        String scenario = "single-round";
        if (args.length == 2 && "--scenario".equals(args[0])) {
            scenario = args[1];
        }
        if (!List.of("dry-run", "without-tools", "with-tools", "single-round", "broken-protocol")
                .contains(scenario)) {
            emit("run.failed", "error", "未知场景: " + scenario);
            System.exit(2);
        }
        try {
            int exitCode = "dry-run".equals(scenario) ? dryRun() : runReal(scenario);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (ModelApiException | ToolRequestException exception) {
            emit("run.failed", "error", exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            emit("run.failed", "error", exception.toString());
            System.exit(2);
        }
    }

    record ToolRequest(String id, String name, String city) {
    }

    static final class ToolRequestException extends RuntimeException {
        ToolRequestException(String message) {
            super(message);
        }

        ToolRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class ModelApiException extends RuntimeException {
        ModelApiException(String message) {
            super(message);
        }
    }
}
