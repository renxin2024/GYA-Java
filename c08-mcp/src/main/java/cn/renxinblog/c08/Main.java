package cn.renxinblog.c08;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;

import java.util.List;
import java.util.Map;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("server")) {
            ServerMain.run();
            return;
        }

        String classpath = System.getProperty("java.class.path");
        ServerParameters parameters = ServerParameters.builder("java")
                .args("-cp", classpath, ServerMain.class.getName(), "server")
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(System.err::println);
        McpSyncClient client = McpClient.sync(transport).build();

        try {
            var init = client.initialize();
            System.out.println("[1] 初始化 MCP Server: " + init.serverInfo().name());

            ListToolsResult tools = client.listTools();
            System.out.println("[2] 发现工具: " + tools.tools().stream().map(tool -> tool.name()).toList());

            CallToolResult result = client.callTool(CallToolRequest.builder("add")
                    .arguments(Map.of("a", 2, "b", 3))
                    .build());
            System.out.println("[3] 调用 add(2, 3): " + text(result));

            try {
                client.callTool(CallToolRequest.builder("invalid_tool_name")
                        .arguments(Map.of())
                        .build());
            }
            catch (McpError error) {
                System.out.println("[4] 错误场景: McpError; " + error.getMessage());
            }
        }
        finally {
            client.closeGracefully();
        }
    }

    private static String text(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .findFirst()
                .orElse("<empty>");
    }
}
