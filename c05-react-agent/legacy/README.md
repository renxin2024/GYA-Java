# C05 演示（Java 21）：手写 ReAct Agent——循环的诞生

与 Python 版四文件等价的 Java 单文件实现（对应 react_loop.py + tools.py + state.py）。

ReAct 主循环：模型"想"（reasoning_content = Thought，tool_calls = Action），代码"做"（执行工具）+ "看"（Observation 回喂），直到 Final Answer。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export DEEPSEEK_API_KEY=sk-你的key
./gradlew :c05-react-agent:run
# 自定义任务：
./gradlew :c05-react-agent:run --args="北京天气怎么样？顺便算一下 123*456"
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出（关键部分）

```
=== Step 1 ===
[Thought] The user wants three things: check weather, calculate, summarize...
[Action] 调用 get_weather({"city":"北京"})
[Observation] 北京: 多云，25℃，东北风 3 级
[Action] 调用 calculator({"expression":"123*456"})
[Observation] 56088.0

=== Step 2 ===
[Thought] I have both results. Now I'll summarize...
[Final Answer] 北京今天多云，气温25℃；123 × 456 = 56088。
```

## 常见坑

1. **HTTP 400 `reasoning_content` 错误**：DeepSeek thinking 模式下，回喂 assistant 消息必须**原样**包含 `reasoning_content`。代码里 `messages.add((ObjectNode) msg)` 直接回传完整消息。
2. **模型停不下来**：`maxSteps` 上限 + 每次把 Observation 回喂，模型看到"已查过"通常就会收敛。生产里还会加"重复调用检测"。
