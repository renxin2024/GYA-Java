# C03 演示（Java 21）：模型"会调用工具"的能力是从哪来的？

与 Python 版 `train_compare.py` 完全同构的 Java 等价实现。

同一个问题（北京和上海天气，需要两个工具调用），三种问法对比：

| 方式 | 输出 | 说明 |
|------|------|------|
| **A) 裸问**（无 tools） | 文本回答"我无法实时获取天气" | 模型不知道『可以用』工具 |
| **B) 带 tools 参数** | 2 个结构化 `tool_calls`，各带独立 id | 后训练格式 + API 层保证 |
| **C) 纯 prompt 手写格式** | 靠 system prompt 要求"输出 JSON"，无 API 保证 | 多次运行会漏调用、格式不稳 |

实测（deepseek-v4-flash，2026-08-20）：方式 B **5/5** 次完整输出两个 tool_calls；方式 C 只有 **3/5** 次能被解析器完整捞到（其余漏掉一个城市）。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export DEEPSEEK_API_KEY=sk-你的key
./gradlew :c03-function-calling-training:run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出（关键部分）

```
[A] 不带 tools 参数（模型只会文本补全）
content: '很抱歉，我无法直接接入实时的气象数据中心...'
tool_calls: []

[B] 带 tools 参数（API 层注入 + 后训练格式约定）
tool_calls: [{"function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}},...]
>>> 模型输出 2 个结构化对象，每个带独立 id

[C] 不带 tools 参数，只靠 system prompt 要求输出 JSON（跑 5 次）
第1次: 解析到 2 个工具调用  ✓ 数量正好
...
第5次: 解析到 1 个工具调用  ✗ 漏了 1 个

[D] 稳定性对比
方式 B（带 tools 参数）: 5/5 次输出完整的 2 个 tool_calls
方式 C（纯 prompt 手写）: 3/5 次能被解析器完整捞到 2 个调用
```

（模型名、具体次数可能随模型版本变化，但形状一致：**B 稳定、C 会漏**。）
