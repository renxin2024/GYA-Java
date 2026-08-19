# C02 Java 版：Function Calling 第一性原理

GYA 系列第 2 篇的 Java 21 + Gradle 实现。Python 版见 [GYA/c02-function-calling](https://github.com/renxin2024/GYA/tree/main/c02-function-calling)。

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
├── settings.gradle.kts          # 工程名
├── build.gradle.kts             # application 插件 + Jackson 依赖 + Java 21 toolchain
└── src/main/java/com/renxin/gya/c02/
    └── Main.java                # 完整闭环主程序
```

## 工程结构说明

标准 Gradle 工程（`application` 插件 + Java 21 toolchain + Jackson 依赖），`gradle run` 一键运行。与早期「单文件零依赖」演示相比，用 Jackson 处理 JSON 让代码只关心业务逻辑——这也是正文想传达的：**生产环境永远用 JSON 库，别手写解析**。

## 常见坑

| 症状 | 原因 | 解法 |
|------|------|------|
| `gradle: command not found` | 未装 Gradle | 按上文安装 |
| `JAVA_HOME` 或 toolchain 报错 | JDK 不是 21 | `java -version` 确认 21+ |
| 401 `Invalid API key` | Key 未设置 | `export DEEPSEEK_API_KEY=sk-...` |
| HTTP 400 `role 'tool'` | 回喂缺 assistant.tool_calls | 按正文消息序列拼完整 |