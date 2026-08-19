# C02 Java 版：Function Calling 第一性原理

GYA 系列第 2 篇的 Java 21 + Gradle 实现。Python 版见 [GYA/c02-function-calling](https://github.com/renxin2024/GYA/tree/main/c02-function-calling)。

## 技术栈

| 项 | 版本 |
|----|------|
| JDK | 21（LTS） |
| 构建 | Gradle Wrapper 8.14.2（`./gradlew run`，无需预装 Gradle） |
| 包名 | `cn.renxinblog.c02` |
| JSON | Jackson（`jackson-databind`，走阿里云镜像下载） |

## 运行

```bash
export DEEPSEEK_API_KEY=sk-你的key
./gradlew run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出

```
模型: deepseek-v4-flash
问题: 北京现在天气怎么样？
----------------------------------------------
[模型说] 我要调用工具: get_weather({"city": "北京"})
[调用方] 我已执行工具，结果是: 多云，25℃，东北风 3 级
[模型最后说] 北京现在多云，气温25℃，东北风3级。
```

完整演示了「模型只说、代码做」的闭环：声明工具 → 模型输出 tool_calls → 调用方解析执行 → 回喂 → 模型生成最终回答。

## 工程结构

```
c02-function-calling/
├── gradlew / gradle/wrapper/   # Gradle Wrapper（固定 8.14.2，腾讯云镜像）
├── settings.gradle.kts
├── build.gradle.kts            # application 插件 + Jackson(阿里云) + Java 21 toolchain
└── src/main/java/cn/renxinblog/c02/
    └── Main.java               # 完整闭环主程序
```

## 工程结构说明

标准 Gradle 工程（`application` 插件 + Java 21 toolchain + Jackson 依赖），`./gradlew run` 一键运行。用 Jackson 处理 JSON 让代码只关心业务逻辑——这也是正文想传达的：**生产环境永远用 JSON 库，别手写解析**。

## 常见坑

| 症状 | 原因 | 解法 |
|------|------|------|
| `Unable to locate a Java Runtime` | 未装 JDK 或 JAVA_HOME 未配置 | `java -version` 确认 21+ |
| 401 `Invalid API key` | Key 未设置 | `export DEEPSEEK_API_KEY=sk-...` |
| HTTP 400 `role 'tool'` | 回喂缺 assistant.tool_calls | 按正文消息序列拼完整 |