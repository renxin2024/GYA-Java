# C01 Java 版：只会说话的模型（命令行聊天）

GYA 系列第 1 篇的 Java 21 + Gradle 实现。Python 版见 [GYA/c01-chat-only](https://github.com/renxin2024/GYA/tree/main/c01-chat-only)。

## 技术栈

| 项 | 版本 |
|----|------|
| JDK | 21（LTS） |
| 构建 | Gradle Wrapper 8.14.2（`./gradlew run`，无需预装 Gradle） |
| 包名 | `cn.renxinblog.c01` |
| JSON | Jackson（`jackson-databind`，走阿里云镜像下载） |

## 运行

```bash
export DEEPSEEK_API_KEY=sk-你的key
./gradlew run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

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
├── gradlew / gradle/wrapper/   # Gradle Wrapper（固定 8.14.2，腾讯云镜像）
├── settings.gradle.kts
├── build.gradle.kts            # application 插件 + Jackson(阿里云) + Java 21 toolchain
└── src/main/java/cn/renxinblog/c01/
    └── Chat.java               # 命令行聊天主程序
```

## 常见坑

| 症状 | 原因 | 解法 |
|------|------|------|
| `Unable to locate a Java Runtime` | 未装 JDK 或 JAVA_HOME 未配置 | `java -version` 确认 21+ |
| 401 `Invalid API key` | Key 未设置 | `export DEEPSEEK_API_KEY=sk-...` |
| `gradlew` 下载发行版缓慢 | 镜像未生效 | 检查 gradle-wrapper.properties 的 distributionUrl 为腾讯云镜像 |