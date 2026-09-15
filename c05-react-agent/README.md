# C05 演示（Java 21）：最小 ReAct Runtime

对应 Python 版 `react_agent.py`。工具是三个本地确定性只读夹具（`resolve_company_address` / `get_weather` / `request_clarification`），只有 Action 选择依赖真实模型。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java

# 离线验收（不需要 Key）
./gradlew :c05-react-agent:run --args="--offline"
```

看到 `ALL_OFFLINE_CHECKS_PASSED` 即通过。

真实模型路径从 `LLM_API_URL`、`LLM_API_KEY`、`LLM_MODEL` 环境变量读取配置：

```bash
export LLM_API_URL='https://your-openai-compatible-endpoint/v1/chat/completions'
export LLM_API_KEY='your-key'
export LLM_MODEL='your-model'
./gradlew :c05-react-agent:run
```

通过标准：先进入 `WAITING_FOR_CLARIFICATION`，补充“北京总部”后依次调用解析地址、查询北京天气，最终 `COMPLETED`。不要提交密钥或端点。

## 说明

- `src/main/java/cn/renxinblog/c05/ReactAgent.java`：本演示实现
- `legacy/Main.java`：早期四文件版的 Java 实现，与文章不对应，仅供追溯（其 README 同放于 `legacy/`）
