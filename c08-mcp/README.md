# C08 演示（Java 21）：MCP 工具发现与调用

本示例使用官方 MCP Java SDK（2.0.1），通过 STDIO 启动独立 Server，完成初始化、工具发现、工具调用和未知工具错误返回。与 Python 版 `main.py` 的纯 MCP 层能力目标一致。

## 前置环境

| 项 | 要求 |
|----|------|
| JDK | 21（`/usr/libexec/java_home -v 21`） |
| 依赖 | `io.modelcontextprotocol.sdk:mcp:2.0.1`（Gradle 自动拉取） |

> 注意：Gradle 8.14.2 的 Kotlin DSL 无法解析 JDK 25 的版本号，必须用 JDK 21 跑 Gradle。
> `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 后再 `./gradlew ...`。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :c08-mcp:run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出

```text
[1] 初始化 MCP Server: c08-java-demo
[2] 发现工具: [add, get_weather, get_time]
    - add: Add two integers and return the result.
    - get_weather: Get the current weather for a city.
    - get_time: Get the current server time.
[3] 调用 add(2, 3): 5
[4] 错误场景: McpError; Unknown tool: invalid_tool_name
```

> `SLF4J(W): No SLF4J providers were found` 是缺少日志实现的无害警告，不影响功能。
>
> `invalid_tool_name` 是 SDK 错误文案里的固定串，与代码里请求的工具名无关：把 `Main.java` 里的请求名改成别的，这行输出不变（已实测）。它只是「工具不存在」这一类错误的统一提示。

## 与 Python 版的对照

| 能力目标 | Python（正文主语言） | Java（等价实现） |
|---|---|---|
| 工具发现 | `client.list_tools()` | `client.listTools()` |
| 工具调用 | `client.call_tool("add", {...})` | `client.callTool(...)` |
| 未知工具错误 | 返回 `is_error=True` 结果 | 抛 `McpError` 异常 |
| LLM 闭环 | `main.py` 第 [2] 段（DeepSeek） | 不重复展开（见 Python 版） |

> 两种语言对「未知工具」的错误处理形式不同（Python 是结果里的 `is_error` 标志，Java 是 `McpError` 异常），但语义一致：错误都是协议的一部分，客户端能明确识别。
