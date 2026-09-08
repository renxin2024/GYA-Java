package cn.renxinblog.c07;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C07 演示（Java 21 + Gradle）：四层记忆——工作、情节、语义、程序
 *
 * 与 Python 版 memory_demo.py 同构：
 *   [1] 情节记忆（Episodic）：事实落 SQLite，重启读回
 *   [2] 语义记忆（Semantic）：bge-m3 向量化 + Qdrant 检索（含同义改写命中）
 *   [3] 完整闭环：保存记忆 → 提问 → 检索 → 组装上下文 → 模型回答（有/无/无命中三态）
 *   [4] 程序记忆（Procedural）：只点一句，钩第九话 Skill
 *
 * 真实 Embedding 走 Ollama bge-m3（经 hermes-gateway stream 代理，127.0.0.1:11434）；
 * 服务不可达时降级到纯 Java TF-IDF 检索（离线兜底，只做回归）。
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx   # LLM 对照（可选）
 *   export QDRANT_API_KEY=xxx        # Qdrant 认证
 *   ./gradlew :c07-memory:run
 *
 * 依赖：JDK 21 + Jackson + sqlite-jdbc（build.gradle.kts 声明，Gradle 自动拉取）。
 */
public class Main {

    static final String LLM_URL = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com/chat/completions");
    static final String LLM_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");

    static final String EMBEDDING_URL = System.getenv().getOrDefault("EMBEDDING_URL", "http://127.0.0.1:11434");
    static final String EMBEDDING_MODEL = System.getenv().getOrDefault("EMBEDDING_MODEL", "bge-m3");
    static final String QDRANT_URL = System.getenv().getOrDefault("QDRANT_URL", "http://127.0.0.1:6333");
    static final String QDRANT_API_KEY = System.getenv().getOrDefault("QDRANT_API_KEY", "");
    static final String COLLECTION = System.getenv().getOrDefault("MEMORY_COLLECTION", "gya_c07_memories");
    static final String DB_PATH = System.getenv().getOrDefault("MEMORY_DB_PATH", "gya_c07_memory.db");

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    // ===============================================================
    // LLM（只做"无记忆 vs 有记忆"对照，核心机制不依赖它）
    // ===============================================================
    static String callLLM(List<ObjectNode> messages) throws Exception {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        ArrayNode arr = payload.putArray("messages");
        messages.forEach(arr::add);
        payload.put("stream", false);

        HttpRequest req = HttpRequest.newBuilder(URI.create(LLM_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + LLM_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(resp.body());
        if (!root.has("choices")) throw new RuntimeException("API 错误: " + resp.body());
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    // ===============================================================
    // 工作记忆（Working）：内存 messages，窗口内
    // ===============================================================
    static final List<ObjectNode> WORKING = new ArrayList<>();

    // ===============================================================
    // 情节记忆（Episodic）：SQLite 落盘，重启读回
    // ===============================================================
    static Connection newDb() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        try (Statement st = c.createStatement()) {
            // 默认保持 SQLite 的标准 journal（WAL 之外默认的 DELETE 模式），
            // 保留崩溃安全与事务回滚能力——对讲持久化记忆的示例，安全是第一位的。
            //
            // 只有在受限沙箱环境（如 CI / 容器化 shell）里，seatbelt 策略会拦截
            // journal 文件的 unlink 调用、报 SQLITE_IOERR_DELETE 时，才通过环境变量
            // MEMORY_SQLITE_UNSAFE_NO_JOURNAL=1 显式关闭 journal。这只适用于一次性
            // 实验环境；正常终端请勿开启，因为关闭 journal 意味着崩溃时可能损坏数据库。
            if ("1".equals(System.getenv("MEMORY_SQLITE_UNSAFE_NO_JOURNAL"))) {
                st.execute("PRAGMA journal_mode=OFF");
                st.execute("PRAGMA synchronous=OFF");
            }
            st.execute("CREATE TABLE IF NOT EXISTS facts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "subject TEXT NOT NULL, fact TEXT NOT NULL," +
                    "session_id TEXT NOT NULL, created_at TEXT NOT NULL)");
        }
        return c;
    }

    static void storeFact(Connection c, String subject, String fact, String sessionId) throws Exception {
        try (var ps = c.prepareStatement("INSERT INTO facts(subject, fact, session_id, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, subject);
            ps.setString(2, fact);
            ps.setString(3, sessionId);
            ps.setString(4, java.time.OffsetDateTime.now().toString());
            ps.executeUpdate();
        }
    }

    static List<String> readFacts(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT subject, fact, session_id FROM facts ORDER BY id")) {
            while (rs.next()) {
                out.add("- " + rs.getString("subject") + ": " + rs.getString("fact") + "  (session=" + rs.getString("session_id") + ")");
            }
        }
        return out;
    }

    // ===============================================================
    // 语义记忆（Semantic）：bge-m3 + Qdrant；离线 TF-IDF 兜底
    // ===============================================================
    static List<Double> embedOne(String text) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", EMBEDDING_MODEL);
        body.put("prompt", text);
        HttpRequest req = HttpRequest.newBuilder(URI.create(EMBEDDING_URL + "/api/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode arr = JSON.readTree(resp.body()).path("embedding");
        List<Double> vec = new ArrayList<>();
        arr.forEach(n -> vec.add(n.asDouble()));
        return vec;
    }

    static boolean qdrantAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(QDRANT_URL + "/collections"))
                    .header("api-key", QDRANT_API_KEY)
                    .GET().build();
            CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void ensureCollection() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(QDRANT_URL + "/collections/" + COLLECTION))
                .header("api-key", QDRANT_API_KEY).GET().build();
        int code = CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        if (code == 404) {
            ObjectNode body = JSON.createObjectNode();
            ObjectNode vectors = body.putObject("vectors");
            vectors.put("size", 1024);
            vectors.put("distance", "Cosine");
            HttpRequest put = HttpRequest.newBuilder(URI.create(QDRANT_URL + "/collections/" + COLLECTION))
                    .header("api-key", QDRANT_API_KEY)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            CLIENT.send(put, HttpResponse.BodyHandlers.ofString());
        }
    }

    static void storeSemantic(String text) throws Exception {
        List<Double> vec = embedOne(text);
        long id = Math.floorMod(text.hashCode(), 1_000_000_000_000L);
        ObjectNode body = JSON.createObjectNode();
        ArrayNode points = body.putArray("points");
        ObjectNode p = points.addObject();
        p.put("id", id);
        ArrayNode vecArr = p.putArray("vector");
        vec.forEach(vecArr::add);
        ObjectNode payload = p.putObject("payload");
        payload.put("text", text);
        HttpRequest req = HttpRequest.newBuilder(URI.create(QDRANT_URL + "/collections/" + COLLECTION + "/points?wait=true"))
                .header("api-key", QDRANT_API_KEY)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static List<String> retrieveSemantic(String query) throws Exception {
        return retrieveSemantic(query, 0.0);
    }

    static List<String> retrieveSemantic(String query, double minScore) throws Exception {
        List<Double> vec = embedOne(query);
        ObjectNode body = JSON.createObjectNode();
        ArrayNode vecArr = body.putArray("vector");
        vec.forEach(vecArr::add);
        body.put("limit", 2);
        body.put("with_payload", true);
        HttpRequest req = HttpRequest.newBuilder(URI.create(QDRANT_URL + "/collections/" + COLLECTION + "/points/search"))
                .header("api-key", QDRANT_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode result = JSON.readTree(resp.body()).path("result");
        List<String> out = new ArrayList<>();
        for (JsonNode p : result) {
            double score = p.path("score").asDouble();
            if (score < minScore) continue;  // 相似度阈值：低分噪声视为「无命中」
            String text = p.path("payload").path("text").asText();
            out.add(text + "  score=" + String.format("%.3f", score));
        }
        return out;
    }

    // ---------- 离线 TF-IDF 兜底（无 embedding/qdrant 服务时） ----------
    static final List<String> OFFLINE_DOCS = new ArrayList<>();
    static final Set<String> STOP = Set.of("的", "了", "是", "在", "我", "你", "什么", "那个",
            "这个", "一个", "喜欢", "用户", "还有", "以及", "就是", "会");

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

    static List<String> retrieveOffline(String query) {
        Set<String> qt = tokenize(query);
        return OFFLINE_DOCS.stream()
                .map(d -> Map.entry(cosine(qt, tokenize(d)), d))
                .filter(e -> e.getKey() > 0)
                .sorted((x, y) -> Double.compare(y.getKey(), x.getKey()))
                .limit(2)
                .map(e -> e.getValue() + "  score=" + String.format("%.3f", e.getKey()))
                .toList();
    }

    // ===============================================================
    // main
    // ===============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(64));
        System.out.println("[1] 情节记忆：关键事实落 SQLite，重启读回");
        System.out.println("=".repeat(64));

        Connection db = newDb();
        try (Statement st = db.createStatement()) {
            st.execute("DELETE FROM facts");  // 干净起点
        }
        // 事实「预设写入」：演示不包含「从对话自动提取关键事实」那道工序。
        storeFact(db, "用户名", "张三", "session-A");
        storeFact(db, "偏好", "最近在戒咖啡，想少喝一点", "session-A");
        storeFact(db, "职业", "Java 后端工程师", "session-A");
        System.out.println("情节记忆已写入 SQLite（3 条）：");
        readFacts(db).forEach(f -> System.out.println("  " + f));

        System.out.println("\n（模拟进程重启：新开连接，读回）");
        db.close();
        db = newDb();
        System.out.println("重启后读回的事实：");
        readFacts(db).forEach(f -> System.out.println("  " + f));
        db.close();

        System.out.println("\n" + "=".repeat(64));
        System.out.println("[2] 语义记忆：bge-m3 + Qdrant，跨会话召回（含同义改写）");
        System.out.println("=".repeat(64));

        boolean online = qdrantAvailable();
        if (online) {
            ensureCollection();
            for (String d : List.of("用户最近在戒咖啡，想少喝一点",
                    "用户职业是 Java 后端工程师，擅长并发编程",
                    "用户的博客主题是 AI Agent 开发")) {
                storeSemantic(d);
            }
            for (String q : List.of("用户喝咖啡吗？", "用户想戒掉什么？", "用户职业是什么？", "博客写什么？")) {
                System.out.println("  问「" + q + "」→ 命中 [" + retrieveSemantic(q).get(0) + "]");
            }
        } else {
            System.out.println("⚠️  Embedding/Qdrant 不可达，降级到离线 TF-IDF 检索");
            OFFLINE_DOCS.addAll(List.of("用户最近在戒咖啡，想少喝一点",
                    "用户职业是 Java 后端工程师，擅长并发编程",
                    "用户的博客主题是 AI Agent 开发"));
            for (String q : List.of("用户喝咖啡吗？", "用户想戒掉什么？", "用户职业是什么？", "博客写什么？")) {
                System.out.println("  问「" + q + "」→ " + retrieveOffline(q));
            }
        }

        System.out.println("\n" + "=".repeat(64));
        System.out.println("[3] 完整闭环：检索结果 → 组装上下文 → 模型回答");
        System.out.println("=".repeat(64));
        if (LLM_KEY.isEmpty()) {
            System.out.println("  （未设置 DEEPSEEK_API_KEY，跳过 LLM 对照）");
        } else {
            // 复用上面的降级路径：在线用 Qdrant，离线用 TF-IDF。
            String q = "我最近在戒咖啡，聚餐时该注意什么？";

            // 情况一：有记忆——检索命中偏好（带阈值过滤低分噪声），组装进 system 消息。
            System.out.println("  问：" + q);
            List<String> hits = online ? retrieveSemantic(q, 0.5) : retrieveOffline(q);
            if (hits.isEmpty()) {
                System.out.println("  ⚠️ 检索无命中，跳过有记忆分支");
            } else {
                System.out.println("  检索命中 " + hits.size() + " 条，组装进 system 消息：");
                for (String h : hits) System.out.println("    - " + h);
                ObjectNode sys = JSON.createObjectNode();
                sys.put("role", "system");
                sys.put("content", "你可以参考下面这些关于当前用户、检索自记忆库的信息：\n"
                        + String.join("\n", hits));
                ObjectNode user = JSON.createObjectNode();
                user.put("role", "user");
                user.put("content", q);
                String r = callLLM(List.of(sys, user));
                System.out.println("  模型（有记忆）: " + truncate(r, 120));
            }

            // 情况二：无记忆——同一问题，不给任何检索结果。
            System.out.println("\n  问：" + q + "（不给任何记忆）");
            ObjectNode user2 = JSON.createObjectNode();
            user2.put("role", "user");
            user2.put("content", q);
            String r2 = callLLM(List.of(user2));
            System.out.println("  模型（无记忆）: " + truncate(r2, 120));

            // 情况三：无命中——问一个记忆库里没有的话题，检索分数低于阈值。
            String q3 = "我上个月去过的那个地方，叫什么名字？";
            List<String> hits3 = online ? retrieveSemantic(q3, 0.5) : retrieveOffline(q3);
            System.out.println("\n  问：" + q3);
            if (hits3.isEmpty()) {
                System.out.println("  （检索无命中，如实告知模型没有相关信息）");
                ObjectNode user3 = JSON.createObjectNode();
                user3.put("role", "user");
                user3.put("content", q3);
                String r3 = callLLM(List.of(user3));
                System.out.println("  模型（无命中）: " + truncate(r3, 120));
            } else {
                System.out.println("  （意外命中，跳过）");
            }
        }

        System.out.println("\n" + "=".repeat(64));
        System.out.println("[4] 程序记忆：记「怎么做」，而不是「记了什么事实」（钩第九话 Skill）");
        System.out.println("=".repeat(64));
        System.out.println("  前三层记住的是「关于世界与经历的信息」；第四层记住的是「怎么做一件事」。");
        System.out.println("  把「完成任务的流程/规则」固化下来，就是 Skill——第九话展开。");

        System.out.println("\n" + "=".repeat(64));
        System.out.println("核心结论：上下文窗口 ≠ 记忆。");
        System.out.println("  模型单次调用不会自动记住历史，跨会话的信息需要由应用这层保存、检索、再喂回。");
    }

    static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
