# C01 Java 版：从聊天输出到提示词动作协议

这是第一话 Python demo 的 Java 21 等价实现，用于对照验证关键行为：

1. 普通聊天只打印模型文本，不执行动作；
2. 提示词约定动作 JSON；
3. Java 校验工具名和参数后，才调用本地 `get_weather()`。

天气函数返回本地固定的天气演示数据，不代表实时天气。

## 环境

- JDK 21
- 仓库自带 Gradle Wrapper 8.14.2
- 一个 OpenAI 兼容的模型 API Key

```bash
export LLM_API_KEY="你的 Key"
export LLM_MODEL="deepseek-v4-flash"
export LLM_API_URL="https://api.deepseek.com"
```

也可以继续使用 `DEEPSEEK_API_KEY`。

## 运行

从 GYA-Java 仓库根目录执行：

```bash
./gradlew :c01-chat-only:run
```

也可以只运行一种模式：

```bash
./gradlew :c01-chat-only:run --args='--mode chat'
./gradlew :c01-chat-only:run --args='--mode prompt-tool'
```

预期行为与 Python 版一致：第一段打印“Java 执行动作：否”；第二段在动作 JSON 校验通过后，打印 `get_weather` 的调用和本地演示结果。

## 离线测试

```bash
./gradlew :c01-chat-only:test
```

测试覆盖合法动作、Markdown 代码围栏、未知动作、多余参数和空城市名。离线测试只验证控制流，不能替代真实模型调用。
