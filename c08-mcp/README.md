# C08 演示（Java 21）：MCP 工具发现与调用

本示例使用官方 MCP Java SDK，通过 STDIO 启动独立 Server，完成初始化、工具发现、工具调用和未知工具错误返回。

## 运行

```bash
./gradlew :c08-mcp:run
```

## 预期输出

```text
[1] 初始化 MCP Server: c08-java-demo
[2] 发现工具: [add]
[3] 调用 add(2, 3): 5
[4] 错误场景: McpError; Unknown tool: invalid_tool_name
```
