package cn.renxinblog.c16;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 一个轻量的 Java 21 SSE 服务，用来演示一次模拟的 Agent Run。
 *
 * <p>本示例刻意不调用真实 LLM。固定事件序列把传输问题单独抽出来：一个保持打开的 HTTP
 * 响应承载多条命名 Agent 事件，每条事件都以空行结束。</p>
 */
public final class Main {
    // Jackson 只序列化应用层 payload。SSE 分帧仍由 encode() 负责，从而把协议层与
    // 业务数据层清晰分开。
    private static final ObjectMapper JSON = new ObjectMapper();
    // 真实编排器会从 Run 状态存储或事件总线发布这些事件。这里保持确定性，使 Python
    // 与 Java 读者无需 API Key 或依赖模型输出，也能对照同一条生命周期。
    private static final List<Event> EVENTS = List.of(
            event("1", "text.delta", Map.of("runId", "run_demo", "delta", "我先运行测试。")),
            event("2", "tool.started", Map.of("runId", "run_demo", "tool", "run_tests", "callId", "call_1")),
            event("3", "tool.completed", Map.of("runId", "run_demo", "callId", "call_1", "summary", "3 个测试失败")),
            event("4", "run.completed", Map.of("runId", "run_demo"))
    );

    // `id` 是重连时作为 Last-Event-ID 带回的续传游标；`type` 会成为 SSE 的
    // `event:` 字段。事件名称和 payload 结构都不是 SSE 强制规定的，而是本示例的
    // Agent 事件契约。
    private record Event(String id, String type, Map<String, String> data) {}

    private static Event event(String id, String type, Map<String, String> data) {
        return new Event(id, type, new LinkedHashMap<>(data));
    }

    private static byte[] encode(Event event) throws IOException {
        // SSE 是 UTF-8 的按行协议。最后的空行是事件边界；删除它会让 EventSource
        // 持续等待，而不是把前面的字段作为一条完整事件分发出去。
        String frame = "id: " + event.id() + "\n"
                + "event: " + event.type() + "\n"
                + "data: " + JSON.writeValueAsString(event.data()) + "\n\n";
        return frame.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        int port = 8766;
        boolean once = false;
        for (String arg : args) {
            if (arg.equals("--once")) once = true;
            if (arg.startsWith("--port=")) port = Integer.parseInt(arg.substring("--port=".length()));
        }

        // 这个 latch 没有业务含义，只为 --once 模式服务：固定事件消费完成后，进程可以
        // 确定性停止。
        CountDownLatch done = new CountDownLatch(1);
        // 只绑定回环地址；这是本地学习服务，不是可直接对公网提供的生产 endpoint。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        boolean stopAfterOneRequest = once;
        // SSE 是只读下行通道。真实系统会通过独立 POST endpoint 创建 Run，再依据 runId
        // 校验这个 GET 请求是否有订阅权限。
        server.createContext("/events", exchange -> stream(exchange, stopAfterOneRequest ? done : null));
        server.createContext("/", Main::notFound);
        server.start();
        System.out.printf("SSE endpoint: http://127.0.0.1:%d/events%n", port);
        System.out.printf("Open it with: curl -N http://127.0.0.1:%d/events%n", port);

        if (stopAfterOneRequest) {
            done.await();
            server.stop(0);
        } else {
            // HttpServer 已启动工作线程，但 main 仍需阻塞，常规模式才能持续接受订阅者，
            // 直到开发者按 Ctrl-C 停止。
            new CountDownLatch(1).await();
        }
    }

    private static void stream(HttpExchange exchange, CountDownLatch done) throws IOException {
        // 保持 endpoint 职责单一，使 HTTP 方法语义清晰：该方法不接收命令，只向客户端
        // 流式输出服务端事件。
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        // 响应长度设为 0 会启用分块输出。事件到来期间 body 保持打开，而不是先在内存
        // 缓冲完整响应。
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (var body = exchange.getResponseBody()) {
            for (Event event : EVENTS) {
                body.write(encode(event));
                // 每个完整帧都 flush。这是应用侧实现渐进传输的前提；生产部署中网关仍可能
                // 需要单独配置缓冲策略。
                body.flush();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            // 只有 --once 会传入 latch。普通服务模式下，这个客户端收完四个固定事件后，
            // 服务端仍会继续运行并等待下一个客户端。
            if (done != null) done.countDown();
        }
    }

    private static void notFound(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(404, -1);
    }
}
