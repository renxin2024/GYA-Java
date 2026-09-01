# C01 演示（Java 21）：一次最小调用，看清职责边界

一个只做一件事的最小客户端：把「一次调用里谁发了什么、谁收到了什么」打印成 Trace。它不会自动查天气、也不会调用工具——这正是 C01 要你看清的边界。

## 前置环境

| 项 | 要求 |
|----|------|
| JDK | 21（LTS） |
| 构建 | 仓库根目录 `./gradlew`（无需预装 Gradle） |
| 依赖 | 本例子模块只用 JDK 标准库，不依赖 Jackson |
| API Key | DeepSeek 官方 Key（仅真实调用需要，`--dry-run` 不需要） |

Python 等价实现见 **GYA** 仓库的 `c01-chat-only/main.py`。

## 运行

```bash
# 不联网：看 Runtime 会组装出什么样的请求
./gradlew :c01-chat-only:run --args="--dry-run 上海今天天气怎么样？"

# 注入一条 system 级运行时提示
RUNTIME_CONTEXT="只回答可以从请求证明的事实" ./gradlew :c01-chat-only:run --args="--dry-run 上海今天天气怎么样？"

# 真实调用
export DEEPSEEK_API_KEY=sk-你的key
./gradlew :c01-chat-only:run --args="上海今天天气怎么样？"
```

## 预期输出

空上下文 dry-run：

```json
{"event":"context.skipped","owner":"client_runtime","source":"env:RUNTIME_CONTEXT","reason":"empty_optional_context"}
{"event":"request.prepared","owner":"client_runtime","method":"POST","url":"https://api.deepseek.com/chat/completions","headers":{"Content-Type":"application/json","Authorization":"Bearer <redacted>"},"body":{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"上海今天天气怎么样？"}],"stream":false}}
{"event":"run.finished","owner":"client_runtime","outcome":"dry_run_no_network"}
```

非空 `RUNTIME_CONTEXT` 时，第一条 Trace 变为 `context.prepared`（含 `role=system`、字节数与 SHA-256），`request.prepared` 的 `messages` 变成 `system → user`，且 system 原文显示为 `<redacted>`。

## 失败排查

- 编译期解析到旧版 JDK → 确认 `java -version` 是 21，仓库根目录 `./gradlew` 自带 Wrapper。
- 真实调用认证失败 → 检查 `DEEPSEEK_API_KEY`（或 `LLM_API_KEY`）是否已 `export`。
- 真实调用网络超时/连不上 → 换网络或确认代理；`--dry-run` 不联网，可先单独验证。
