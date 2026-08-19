# C01 Java 版：只会说话的模型（命令行聊天）

GYA 系列第 1 篇的 Java 21 等价实现。Python 版见 [GYA/c01-chat-only](https://github.com/renxin2024/GYA/tree/main/c01-chat-only)。

## 运行（JDK 21，单文件，零依赖）

```bash
export DEEPSEEK_API_KEY=sk-你的key
java Chat.java
```

启动后直接输入文字回车对话；`exit` / `quit` / `退出` 退出。

## 预期输出

```
你 > 你好，你是谁？
模型 > 你好呀！我是 DeepSeek，由深度求索公司创造的 AI 助手。……
你 > 你会查天气吗？
模型 > 目前我无法直接查询实时天气信息……
你 > exit
```

## 为什么零依赖

- HTTP 用 JDK 自带的 `java.net.http.HttpClient`（Java 11+）
- JSON 字段用正则提取（本演示只需要 `content` 字段）
- 生产环境请换 Jackson/Gson 等 JSON 库

## 常见坑

| 症状 | 原因 | 解法 |
|------|------|------|
| `java: not found` / 版本错误 | 未装 JDK 21 | `java -version` 确认 21+ |
| 401 `Invalid API key` | Key 未设置 | `export DEEPSEEK_API_KEY=sk-...` |