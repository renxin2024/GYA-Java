# C07 演示（Java 21）：四层记忆——工作、情节、语义、程序

与 Python 版 `memory_demo.py` 同构的 Java 等价实现。

四层记忆：工作（内存 messages）+ 情节（SQLite 落盘）+ 语义（bge-m3 + Qdrant 检索）+ 程序（只点一句，钩 C09）。

## 前置环境

| 项 | 要求 |
|----|------|
| JDK | 21（`/usr/libexec/java_home -V` 查看，`JAVA_HOME` 指到 21） |
| Embedding | Ollama `bge-m3`（经 hermes-gateway stream 代理，`127.0.0.1:11434`） |
| Qdrant | 向量库 `127.0.0.1:6333`（需 `QDRANT_API_KEY`） |
| LLM（可选） | DeepSeek Key，只做「无记忆 vs 有记忆」对照 |

> 注意：Gradle 8.14.2 的 Kotlin DSL 无法解析 JDK 25 的版本号，必须用 JDK 21 跑 Gradle。
> `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 后再 `./gradlew ...`。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export QDRANT_API_KEY=你的key
export DEEPSEEK_API_KEY=sk-你的key
./gradlew :c07-memory:run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出（关键部分）

```
[1] 情节记忆：关键事实落 SQLite，重启读回
重启后读回的事实：
  - 用户名: 张三
  - 偏好: 喝茶，尤其是龙井
  - 职业: Java 后端工程师

[2] 语义记忆：bge-m3 + Qdrant，跨会话召回（含同义改写）
  问「用户喜欢喝什么？」→ 命中 [用户喜欢喝茶，尤其是龙井  score=0.753]
  问「用户爱喝什么饮料？」→ 命中 [用户喜欢喝茶，尤其是龙井  score=0.705]
  问「用户职业是什么？」→ 命中 [用户职业是 Java 后端工程师，擅长并发编程  score=0.704]

[3] 无记忆对照：模型没有上下文时，答不上'我是谁'
  模型: 在没有上下文的情况下，我无法知道你是谁...

[4] 程序记忆：事实会过期，方法可复用（钩第九话 Skill）
```

同义改写命中（「爱喝」→「喜欢喝」，score 0.705）与 Python 版一致，是真实 Embedding 的价值。

## 说明

- 语义记忆与 Python 版一致：真实 bge-m3（1024 维）+ Qdrant 检索；Embedding/Qdrant 不可达时自动降级到纯 Java TF-IDF 兜底（只做回归，不冒充真实 Embedding）。
- 情节记忆用 `sqlite-jdbc`（`org.xerial:sqlite-jdbc:3.46.0.0`）落盘；`newDb()` 里 `PRAGMA journal_mode=OFF` 是为了绕过受限沙箱环境对 SQLite journal unlink 的拦截，正常本地终端可去掉。
