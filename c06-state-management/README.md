# C06 演示（Java 21）：状态管理——手写状态机版

对应 Python 版 `state_machine.py`（Java 无 LangGraph 等价官方库，本文件实现手写状态机等价版本）。

核心演示：**图管流程，LLM 管内容**——路由是确定性代码（`next_step` 字段跳转，零 token），LLM 只在"语义理解"和"总结"两个节点被调用。Java 版的"图声明"就是 `NODES` 注册表（nodeName -> Function）。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export DEEPSEEK_API_KEY=sk-你的key
./gradlew :c06-state-management:run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出（与 Python 手写版一致）

```
=== Node: parse_intent ===   intent=[get_weather, calculator]
=== Node: execute_tools ===  get_weather → 多云，25℃，东北风 3 级
                             calculator → 56088.0
=== Node: summarize ===      北京天气多云、25℃；123×456 的结果是 56088...

最终答案: ...
执行轨迹: intent=[get_weather, calculator] results={get_weather=..., calculator=...}
```

## 说明

- **为什么没有 Java 版 LangGraph？** LangGraph 是 Python 生态库（基于 asyncio/TypedDict 设计），无官方 Java 等价实现。Java 生态的状态机方案（Spring StateMachine 等）思想类似但 API 不同。本演示聚焦**手写状态机**这一通用概念，Python 版额外对照 LangGraph。
- 想在 Java 里表达 StateGraph 的声明式思想，`NODES` 注册表 + `next_step` 路由表已经足够——这就是"图"的最小形态。
