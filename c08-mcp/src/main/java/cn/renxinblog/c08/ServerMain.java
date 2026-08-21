package cn.renxinblog.c08;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        run();
    }

    static void run() throws InterruptedException {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "integer"),
                        "b", Map.of("type", "integer")),
                "required", List.of("a", "b"));

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("c08-java-demo", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .toolCall(
                        Tool.builder("add", schema)
                                .description("Add two integers")
                                .build(),
                        (exchange, request) -> {
                            int a = ((Number) request.arguments().get("a")).intValue();
                            int b = ((Number) request.arguments().get("b")).intValue();
                            return CallToolResult.builder()
                                    .content(List.of(new io.modelcontextprotocol.spec.McpSchema.TextContent(
                                            Integer.toString(a + b))))
                                    .build();
                        })
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
