# C04 演示（Java 21）：工具注册表与 ToolResponse 协议

与 Python 版 `registry_demo.py` 完全同构的 Java 等价实现。

ToolRegistry（注册/发现/调用/下线）+ ToolResponse（status/text/data/error_info）四场景演示：正常闭环、UNKNOWN_TOOL、INVALID_PARAM、下线后模型不再调用。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export DEEPSEEK_API_KEY=sk-你的key
./gradlew :c04-tool-registry:run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出（关键部分）

```
已注册工具: [get_weather, calculator, search_notes]

[1] 正常闭环
  [模型说] 调用 get_weather({"city":"北京"})
  [注册表] status=SUCCESS, text=北京: 多云，25℃，东北风 3 级
  [模型说] 调用 calculator({"expression":"123*456"})
  [注册表] status=SUCCESS, text=56088.0

[2] 调用不存在的工具（模拟模型幻觉）
status=ERROR, error_code=UNKNOWN_TOOL

[3] 参数不对（calculator 缺 expression）
status=ERROR, error_code=EXECUTION_ERROR

[4] 下线工具：unregister('get_weather')
[模型直接回答] 很抱歉，我目前没有查询实时天气的工具...
```

## 常见坑

1. **HTTP 400 `reasoning_content` 错误**：DeepSeek thinking 模式下，回喂 assistant 消息必须**原样**包含 `reasoning_content`。代码里 `messages.add((ObjectNode) msg)` 直接回传完整消息，不要手动重建。
2. **多个 tool_calls**：assistant 消息（含全部 tool_calls）只 append 一次，然后每个 tool_call 结果各 append 一条 `role=tool` 消息。

## 与 Python 版的差异（如实说明）

| 场景 | Python 版 | Java 版 |
|------|-----------|---------|
| [3] calculator 缺参数 | `INVALID_PARAM`（TypeError 捕获） | `EXECUTION_ERROR`（简化计算器先走表达式校验，空参数触发非法字符异常） |

核心一致：**错误显式化，程序可编程地判断**。差异仅来自两语言参数校验的时机不同，不影响本文论点——你的实现里用哪个错误码都行，关键是别返回裸字符串。
