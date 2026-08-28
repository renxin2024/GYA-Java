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

/** A dependency-light Java 21 SSE server for one simulated Agent Run. */
public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Event> EVENTS = List.of(
            event("1", "text.delta", Map.of("runId", "run_demo", "delta", "我先运行测试。")),
            event("2", "tool.started", Map.of("runId", "run_demo", "tool", "run_tests", "callId", "call_1")),
            event("3", "tool.completed", Map.of("runId", "run_demo", "callId", "call_1", "summary", "3 个测试失败")),
            event("4", "run.completed", Map.of("runId", "run_demo"))
    );

    private record Event(String id, String type, Map<String, String> data) {}

    private static Event event(String id, String type, Map<String, String> data) {
        return new Event(id, type, new LinkedHashMap<>(data));
    }

    private static byte[] encode(Event event) throws IOException {
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

        CountDownLatch done = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        boolean stopAfterOneRequest = once;
        server.createContext("/events", exchange -> stream(exchange, stopAfterOneRequest ? done : null));
        server.createContext("/", Main::notFound);
        server.start();
        System.out.printf("SSE endpoint: http://127.0.0.1:%d/events%n", port);
        System.out.printf("Open it with: curl -N http://127.0.0.1:%d/events%n", port);

        if (stopAfterOneRequest) {
            done.await();
            server.stop(0);
        } else {
            new CountDownLatch(1).await();
        }
    }

    private static void stream(HttpExchange exchange, CountDownLatch done) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (var body = exchange.getResponseBody()) {
            for (Event event : EVENTS) {
                body.write(encode(event));
                body.flush();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            if (done != null) done.countDown();
        }
    }

    private static void notFound(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(404, -1);
    }
}
