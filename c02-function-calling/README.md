# C02 Java 版：Function Calling 第一性原理

GYA 系列第 2 篇的 Java 21 等价实现。Python 版见 [GYA/c02-function-calling](https://github.com/renxin2024/GYA/tree/main/c02-function-calling)。

## 运行（JDK 21，单文件，零依赖）

```bash
cd java
export DEEPSEEK_API_KEY=sk-你的key
java Main.java
```

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

## Java 版特有的三个坑（正文提到过的机制约束）

1. **`arguments` 是字符串形态的 JSON**：API 返回 `"arguments": "{\"city\": \"北京\"}"`（转义字符串），要先 unescape 再取字段。
2. **tool 消息必须回显 assistant.tool_calls**：直接拼一条 `role=tool` API 会 400 报错，必须先带 assistant 的 tool_calls 记录。
3. **arguments 嵌入外层 JSON 要二次转义**：回喂时 arguments 作为字符串值，内部引号需转义（`toJsonString`）。

生产环境这些都交给 Jackson/Gson；这里刻意手写是为了看清机制。

## 常见坑

| 症状 | 原因 | 解法 |
|------|------|------|
| `java: not found` | 未装 JDK 21 | 安装 Temurin/OpenJDK 21 |
| HTTP 400 `role 'tool'` | 回喂时少了 assistant.tool_calls | 按正文消息序列拼完整 |
| city 为空 | 没对 arguments 做 unescape | 先反转义再解析 |