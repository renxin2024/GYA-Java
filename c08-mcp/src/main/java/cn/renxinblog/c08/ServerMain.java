package cn.renxinblog.c08;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.time.OffsetDateTime;
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

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("c08-java-demo", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .toolCall(
                        Tool.builder("add", addSchema())
                                .description("Add two integers and return the result.")
                                .build(),
                        (exchange, request) -> {
                            int a = ((Number) request.arguments().get("a")).intValue();
                            int b = ((Number) request.arguments().get("b")).intValue();
                            return textResult(Integer.toString(a + b));
                        })
                .toolCall(
                        Tool.builder("get_weather", weatherSchema())
                                .description("Get the current weather for a city. 演示用的确定性数据，不真的联网查天气。")
                                .build(),
                        (exchange, request) -> {
                            String city = String.valueOf(request.arguments().get("city"));
                            return textResult(fakeWeather(city));
                        })
                .toolCall(
                        Tool.builder("get_time", timeSchema())
                                .description("Get the current server time.")
                                .build(),
                        (exchange, request) -> textResult(OffsetDateTime.now().toString()))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }

    private static CallToolResult textResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .build();
    }

    private static String fakeWeather(String city) {
        return switch (city) {
            case "北京" -> "晴，24°C";
            case "上海" -> "多云，27°C";
            case "深圳" -> "阵雨，30°C";
            default -> city + " 暂无数据";
        };
    }

    private static Map<String, Object> addSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "integer"),
                        "b", Map.of("type", "integer")),
                "required", List.of("a", "b"));
    }

    private static Map<String, Object> weatherSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string")),
                "required", List.of("city"));
    }

    private static Map<String, Object> timeSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
