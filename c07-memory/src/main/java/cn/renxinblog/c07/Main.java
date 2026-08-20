package cn.renxinblog.c07;

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
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * C07 演示（Java 21 + Gradle）：三层记忆——上下文、短期、长期
 *
 * 与 Python 版 memory_demo.py 同构：
 *   [1] 短期记忆：用户自报姓名 → 提取 → 后续轮次记忆补位
 *   [2] 无记忆对比：模型说"我不知道你是谁"
 *   [3] 长期记忆：纯 Java 余弦相似度检索（中文 bigram + 停用词）
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   ./gradlew :c07-memory:run
 *
 * 依赖：JDK 21 + Jackson（build.gradle.kts 声明，Gradle 自动拉取）。
 */
public class Main {

    static final String API_URL = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com/chat/completions");
    static final String API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    static JsonNode callLLM(List<ObjectNode> messages) throws Exception {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        ArrayNode arr = payload.putArray("messages");
        messages.forEach(arr::add);
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
    // 短期记忆：会话内关键事实
    // ---------------------------------------------------------------
    static final Map<String, String> WORKING = new LinkedHashMap<>();

    // ---------------------------------------------------------------
    // 长期记忆：纯 Java 余弦检索（中文 bigram + 停用词）
    // ---------------------------------------------------------------
    record LTMEntry(String text, Set<String> tokens) {}

    static final List<LTMEntry> LONG_TERM = new ArrayList<>();

    static final Set<String> STOP = Set.of("用户", "喜欢", "什么", "自己", "我们", "这个", "那个", "一个");

    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            char a = text.charAt(i), b = text.charAt(i + 1);
            if (isCJK(a) && isCJK(b)) {
                String t = "" + a + b;
                if (!STOP.contains(t)) tokens.add(t);
            }
        }
        return tokens;
    }

    static boolean isCJK(char c) {
        return c >= 0x4e00 && c <= 0x9fff;
    }

    static double cosine(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return inter.size() / (Math.sqrt(a.size()) * Math.sqrt(b.size()));
    }

    static void storeLongTerm(String text) {
        LONG_TERM.add(new LTMEntry(text, tokenize(text)));
    }

    static List<LTMEntry> retrieve(String query) {
        Set<String> qt = tokenize(query);
        return LONG_TERM.stream()
                .map(e -> Map.entry(cosine(qt, e.tokens()), e))
                .filter(p -> p.getKey() > 0)
                .sorted((x, y) -> Double.compare(y.getKey(), x.getKey()))
                .limit(2)
                .map(Map.Entry::getValue)
                .toList();
    }

    // ---------------------------------------------------------------
    // Agent：带短期记忆的对话
    // ---------------------------------------------------------------
    static String chat(String userMsg, boolean useWorking) throws Exception {
        List<ObjectNode> messages = new ArrayList<>();
        if (useWorking && !WORKING.isEmpty()) {
            ObjectNode sys = JSON.createObjectNode();
            sys.put("role", "system");
            sys.put("content", "以下是本会话早些时候已确认的事实，回答用户问题时请使用它们：\n"
                    + WORKING.entrySet().stream()
                      .map(e -> "- " + e.getKey() + ": " + e.getValue())
                      .reduce("", (a, b) -> a + b + "\n"));
            messages.add(sys);
        }
        ObjectNode user = JSON.createObjectNode();
        user.put("role", "user");
        user.put("content", userMsg);
        messages.add(user);
        return callLLM(messages).path("content").asText();
    }

    static void extractAndStore(String userMsg, String reply) {
        Pattern[] patterns = {
                Pattern.compile("我叫(.+?)[，。！？\\s]"),
                Pattern.compile("我喜欢(.+?)[，。！？\\s]"),
        };
        String combined = userMsg + reply;
        for (int i = 0; i < patterns.length; i++) {
            var m = patterns[i].matcher(combined);
            if (m.find()) {
                WORKING.put(i == 0 ? "用户名字" : "用户偏好", m.group(1));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.isEmpty()) {
            System.out.println("请先设置 DEEPSEEK_API_KEY 环境变量（https://platform.deepseek.com 获取）");
            return;
        }
        System.out.println("模型: " + MODEL);
        System.out.println("=".repeat(60));

        // [1] 短期记忆
        System.out.println("\n[1] 短期记忆：用户自报姓名，Agent 存下来");
        String u1 = "你好，我叫张三。";
        String r1 = chat(u1, false);
        System.out.println("  用户: " + u1 + "\n  模型: " + truncate(r1, 60));
        extractAndStore(u1, r1);
        System.out.println("  → 短期记忆已存: 用户名字=张三");

        // [2] 记忆补位
        System.out.println("\n[2] 短期记忆：几轮之后，模型已经'忘了'用户名字——但记忆补上了");
        for (int i = 1; i <= 2; i++) chat("帮我写一段关于第" + i + "个主题的文字。", false);
        String r2 = chat("我是谁？我叫什么名字？", true);
        System.out.println("  模型(带记忆): " + truncate(r2, 60));
        String r2b = chat("我是谁？我叫什么名字？", false);
        System.out.println("  模型(无记忆): " + truncate(r2b, 60));

        // [3] 长期记忆
        System.out.println("\n[3] 长期记忆：向量检索");
        storeLongTerm("用户喜欢喝茶，尤其是龙井");
        storeLongTerm("用户职业是 Java 后端工程师，擅长并发编程");
        storeLongTerm("用户的博客主题是 AI Agent 开发");
        for (String q : new String[]{"用户喜欢喝什么？", "用户职业是什么？", "博客写什么？"}) {
            var hits = retrieve(q);
            System.out.println("  问『" + q + "』→ " + hits.stream().map(LTMEntry::text).toList());
        }
    }

    static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
