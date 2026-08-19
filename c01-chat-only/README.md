# C01 Java 版：只会说话的模型（命令行聊天）

GYA 系列第 1 篇的 Java 21 + Gradle 实现。Python 版见 [GYA/c01-chat-only](https://github.com/renxin2024/GYA/tree/main/c01-chat-only)。

## 技术栈

| 项 | 版本 |
|----|------|
| JDK | 21（LTS） |
| 构建 | Gradle 8.x（`application` 插件，`gradle run` 直接跑） |
| JSON | Jackson（`jackson-databind`，build.gradle.kts 声明，自动拉取） |

## 运行

```bash
export DEEPSEEK_API_KEY=sk-你的key
gradle run
```

首次运行 Gradle 会自动下载依赖（需联网）。Gradle 未安装时：
- macOS: `brew install gradle`
- Ubuntu/Debian: `sudo apt install gradle`
- Windows: 从 https://gradle.org/install 安装

## 预期输出

```
你 > 你好，你是谁？
模型 > 你好呀！我是 DeepSeek，由深度求索公司创造的 AI 助手。……
你 > 你会查天气吗？
模型 > 目前我无法直接查询实时天气信息……
你 > exit
```

- 能连续多轮对话（模型记得前文——因为每次请求都把整个历史发回去了）
- 问「你能查天气/订机票/执行操作吗」→ 模型回答「不能」（它只会生成文本）
- 退出：输入 `exit` / `quit` / `退出`，或 Ctrl+C

## 工程结构

```
c01-chat-only/
├── settings.gradle.kts          # 工程名
├── build.gradle.kts             # application 插件 + Jackson 依赖 + Java 21 toolchain
└── src/main/java/com/renxin/gya/c01/
    └── Chat.java                # 命令行聊天主程序
```

## 常见坑

| 症状 | 原因 | 解法 |
|------|------|------|
| `gradle: command not found` | 未装 Gradle | 按上文安装 |
| `JAVA_HOME` 或 toolchain 报错 | JDK 不是 21 | `java -version` 确认 21+ |
| 401 `Invalid API key` | Key 未设置 | `export DEEPSEEK_API_KEY=sk-...` |