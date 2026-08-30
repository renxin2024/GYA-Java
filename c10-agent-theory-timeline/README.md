# C10 ReAct 论文风格最小演示（Java 21）

默认离线 replay 验证 ReAct 的 `Thought → Action → Observation → Final Answer` 控制流，不冒充论文 benchmark 或真实模型实验。

```bash
./gradlew :c10-agent-theory-timeline:run
```

可选真实模型模式：

```bash
export DEEPSEEK_API_KEY='你的 Key'
./gradlew :c10-agent-theory-timeline:run --args="--live"
```

真实模型输出可能不同；跑通标准是最后出现 `actions=1 observations=1 terminated=final_answer`。
