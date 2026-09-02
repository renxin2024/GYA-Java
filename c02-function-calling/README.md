# C02 Java 版：Function Calling 单轮闭环

这是第二话 Python demo 的 Java 21 等价实现，用于验证相同的数据流和安全边界。天气函数返回本地固定的天气演示数据，不代表实时天气。

## 环境

- JDK 21
- 仓库自带 Gradle Wrapper 8.14.2
- DeepSeek API Key

```bash
export LLM_API_KEY="你的 Key"
export LLM_MODEL="deepseek-v4-flash"
export LLM_API_URL="https://api.deepseek.com/chat/completions"
```

也可以继续使用 `DEEPSEEK_API_KEY`。

## 运行

从 GYA-Java 仓库根目录执行正常单轮闭环：

```bash
./gradlew :c02-function-calling:run --args='--scenario single-round'
```

程序会依次记录模型请求工具、Java 执行工具、回传工具结果和模型最终回答。

## 离线测试

```bash
./gradlew :c02-function-calling:test
```

JUnit 覆盖工具 schema、工具请求校验、未知工具、多余参数、正确消息顺序和故意缺失 `assistant(tool_calls)` 的错误顺序。

其他实验场景：

```bash
./gradlew :c02-function-calling:run --args='--scenario dry-run'
./gradlew :c02-function-calling:run --args='--scenario without-tools'
./gradlew :c02-function-calling:run --args='--scenario with-tools'
./gradlew :c02-function-calling:run --args='--scenario broken-protocol'
```
