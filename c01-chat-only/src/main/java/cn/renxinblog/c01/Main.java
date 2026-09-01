package cn.renxinblog.c01;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * C01 演示（Java 21）：一次最小调用，看清模型、API、Runtime 的职责边界。
 *
 * 这个 demo 只做一件事：把「一次调用里谁发了什么、谁收到了什么」打印成 Trace。
 * 它不会自动查天气、也不会调用工具——这正是 C01 要你看清的边界。
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   ./gradlew :c01-chat-only:run --args="上海今天天气怎么样？"
 * 或者不联网只看请求：
 *   ./gradlew :c01-chat-only:run --args="--dry-run 上海今天天气怎么样？"
 */
public final class Main {
    private Main() {}

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static String jsonQuote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static String payload(String model, String prompt, String runtimeContext, boolean forTrace) {
        StringBuilder messages = new StringBuilder("[");
        if (!runtimeContext.isEmpty()) {
            String content = forTrace ? "<redacted>" : runtimeContext;
            messages.append("{\"role\":\"system\",\"content\":")
                    .append(jsonQuote(content)).append("},");
        }
        String userContent = forTrace ? redactSensitiveFields(prompt) : prompt;
        messages.append("{\"role\":\"user\",\"content\":")
                .append(jsonQuote(userContent)).append("}]");
        return "{\"model\":" + jsonQuote(model)
                + ",\"messages\":" + messages
                + ",\"stream\":false}";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String redactResponse(String raw) {
        return raw.replaceAll(
                "\\\"(id|request_id|system_fingerprint)\\\"\\s*:\\s*\\\"[^\\\"]*\\\"",
                "\"$1\":\"<redacted>\"");
    }

    private static String redactSensitiveFields(String text) {
        return text
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "<redacted>")
                .replaceAll("(?<!\\d)\\d{17}[\\dXx](?!\\d)", "<redacted>");
    }

    private static String argumentPrompt(String[] args) {
        for (String arg : args) {
            if (!arg.equals("--dry-run")) return arg;
        }
        return "上海今天的天气和气温是多少？";
    }

    public static void main(String[] args) throws Exception {
        boolean dryRun = java.util.Arrays.asList(args).contains("--dry-run");
        String apiUrl = env("LLM_API_URL");
        if (apiUrl.isEmpty()) apiUrl = "https://api.deepseek.com/chat/completions";
        String model = env("LLM_MODEL");
        if (model.isEmpty()) model = "deepseek-v4-flash";
        String apiKey = env("DEEPSEEK_API_KEY");
        if (apiKey.isEmpty()) apiKey = env("LLM_API_KEY");
        if (!dryRun && apiKey.isEmpty()) {
            System.err.println("请先设置 DEEPSEEK_API_KEY 或 LLM_API_KEY。");
            System.exit(2);
        }

        String runtimeContext = env("RUNTIME_CONTEXT").strip();
        if (runtimeContext.isEmpty()) {
            System.out.println("{\"event\":\"context.skipped\",\"owner\":\"client_runtime\""
                    + ",\"source\":\"env:RUNTIME_CONTEXT\",\"reason\":\"empty_optional_context\"}");
        } else {
            int contentBytes = runtimeContext.getBytes(StandardCharsets.UTF_8).length;
            System.out.println("{\"event\":\"context.prepared\",\"owner\":\"client_runtime\""
                    + ",\"source\":\"env:RUNTIME_CONTEXT\",\"role\":\"system\""
                    + ",\"content_bytes\":" + contentBytes + ",\"sha256\":" + jsonQuote(sha256(runtimeContext))
                    + ",\"content_redacted\":true}");
        }

        String body = payload(model, argumentPrompt(args), runtimeContext, false);
        String tracedBody = payload(model, argumentPrompt(args), runtimeContext, true);
        System.out.println("{\"event\":\"request.prepared\",\"owner\":\"client_runtime\""
                + ",\"method\":\"POST\",\"url\":" + jsonQuote(apiUrl)
                + ",\"headers\":{\"Content-Type\":\"application/json\",\"Authorization\":\"Bearer <redacted>\"}"
                + ",\"body\":" + tracedBody + "}");
        if (dryRun) {
            System.out.println("{\"event\":\"run.finished\",\"owner\":\"client_runtime\",\"outcome\":\"dry_run_no_network\"}");
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String sanitized = redactResponse(response.body());
        System.out.println("{\"event\":\"response.received\",\"owner\":\"model_api\",\"status\":"
                + response.statusCode() + ",\"body_raw\":" + jsonQuote(sanitized) + "}");
        String outcome = response.statusCode() >= 200 && response.statusCode() < 300 ? "success" : "http_error";
        System.out.println("{\"event\":\"run.finished\",\"owner\":\"client_runtime\",\"outcome\":"
                + jsonQuote(outcome) + "}");
        if (!outcome.equals("success")) System.exit(1);
    }
}
