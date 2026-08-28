# C16 演示（Java 21）：SSE 让 Agent Run 变成事件流

这个 Java 21 示例使用 JDK 自带的 `HttpServer` 暴露 `GET /events`，并以 SSE 帧输出一个模拟 Agent Run 的文本增量、工具状态和完成事件。

它不调用 LLM，也不需要 API Key；Jackson 仅用于可靠生成 JSON payload。

## 前置环境

- JDK 21
- `curl`

## 运行

终端一启动服务：

```bash
./gradlew :c16-sse-agent-stream:run --args="--once"
```

终端二订阅事件流：

```bash
curl -N http://127.0.0.1:8766/events
```

`--once` 会在一次完整订阅完成后关闭服务器；不加它则持续运行，按 `Ctrl-C` 停止。

## 预期输出

事件的字段和顺序与 Python 版一致：`text.delta`、`tool.started`、`tool.completed`、`run.completed`。每个事件均由空行结束。

## 常见问题

1. `curl` 没有逐条显示：确认带了 `-N`，以关闭客户端缓冲。
2. JDK 版本不对：运行 `java -version`，确认是 21；本模块按仓库约定使用 Java 21 toolchain。
3. 8766 端口已占用：传入 `--port=8767`，并同步修改 `curl` 地址。
