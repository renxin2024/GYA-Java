package cn.renxinblog.c10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** C10: a small ReAct loop with deterministic replay and optional live mode. */
public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ACTION = Pattern.compile("Action:\\s*lookup\\((\\{.*?})\\)", Pattern.DOTALL);
    private static final String SYSTEM = """
            You are a ReAct agent. Use exactly one line for each field: Thought, Action, Final Answer.
            Available action: lookup({\"query\": \"...\"}). If you use an Action, wait for the Observation.
            Example: Question: Who wrote Hamlet?\nThought: I need an external fact.\nAction: lookup({\"query\":\"author of Hamlet\"})
            """;
    private static final Map<String, String> FACTS = Map.of(
            "capital of france", "Paris is the capital and most populous city of France.",
            "author of hamlet", "William Shakespeare wrote Hamlet."
    );

    private record Trace(int actions, int observations, String terminated) {}

    private static String lookup(String query) { return FACTS.getOrDefault(query.toLowerCase().trim(), "No local fact found."); }

    private static String offline(String observation) {
        return observation == null
                ? "Thought: I need an external fact before answering.\nAction: lookup({\"query\":\"capital of France\"})"
                : "Thought: I have the external fact.\nFinal Answer: The capital of France is Paris.";
    }

    private static String live(String question, List<Map<String, String>> history) throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("DEEPSEEK_API_KEY is required for --live");
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", question));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash"));
        payload.put("messages", messages);
        payload.put("temperature", 0);
        payload.put("stream", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com/chat/completions")))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("LLM HTTP " + response.statusCode());
        return JSON.readTree(response.body()).path("choices").get(0).path("message").path("content").asText();
    }

    private static Trace run(boolean live) throws Exception {
        String question = "What is the capital of France?";
        List<Map<String, String>> history = new ArrayList<>();
        String observation = null;
        int actions = 0, observations = 0;
        for (int step = 0; step < 3; step++) {
            String output = live ? live(question, history) : offline(observation);
            if (output.contains("Final Answer:")) {
                System.out.println("[Final Answer] " + output.split("Final Answer:", 2)[1].trim());
                return new Trace(actions, observations, "final_answer");
            }
            Matcher matcher = ACTION.matcher(output);
            if (!matcher.find()) throw new IllegalStateException("cannot parse model output: " + output);
            JsonNode arguments = JSON.readTree(matcher.group(1));
            String query = arguments.path("query").asText();
            actions++;
            System.out.println("[Thought] " + output.split("Action:", 2)[0].replace("Thought:", "").trim());
            System.out.println("[Action] lookup(" + JSON.writeValueAsString(Map.of("query", query)) + ")");
            observation = lookup(query);
            observations++;
            System.out.println("[Observation] " + observation);
            history.add(Map.of("role", "assistant", "content", output));
            history.add(Map.of("role", "user", "content", "Observation: " + observation));
        }
        throw new IllegalStateException("maximum steps exceeded");
    }

    public static void main(String[] args) throws Exception {
        boolean live = List.of(args).contains("--live");
        Trace trace = run(live);
        System.out.printf("[check] actions=%d observations=%d terminated=%s%n", trace.actions(), trace.observations(), trace.terminated());
    }
}
