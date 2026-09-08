# C07 演示（Java 21）：四层记忆——工作、情节、语义、程序

与 Python 版 `memory_demo.py` 同构的 Java 等价实现。

四层记忆（按「区分依据」划分，存储介质是实现选择）：工作（内存 messages）+ 情节（SQLite 落盘）+ 语义（bge-m3 + Qdrant 检索）+ 程序（只点一句，钩 C09）。

## 前置环境

| 项 | 要求 |
|----|------|
| JDK | 21（`/usr/libexec/java_home -V` 查看，`JAVA_HOME` 指到 21） |
| Embedding | Ollama `bge-m3`（经 hermes-gateway stream 代理，`127.0.0.1:11434`） |
| Qdrant | 向量库 `127.0.0.1:6333`（需 `QDRANT_API_KEY`） |
| LLM（可选） | DeepSeek Key，只做「有记忆 / 无记忆 / 无命中」对照 |

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
  - 偏好: 最近在戒咖啡，想少喝一点
  - 职业: Java 后端工程师

[2] 语义记忆：bge-m3 + Qdrant，跨会话召回（含同义改写）
  问「用户喝咖啡吗？」→ 命中 [用户最近在戒咖啡，想少喝一点  score=0.753]
  问「用户想戒掉什么？」→ 命中 [用户最近在戒咖啡，想少喝一点  score=0.783]
  问「用户职业是什么？」→ 命中 [用户职业是 Java 后端工程师，擅长并发编程  score=0.704]

[3] 完整闭环：检索结果 → 组装上下文 → 模型回答
  问：我最近在戒咖啡，聚餐时该注意什么？
  检索命中 1 条，组装进 system 消息 ...
  模型（有记忆）: 你最近在戒咖啡，聚餐时留意含咖啡因的饮品。
  模型（无记忆）: 我无法判断你的饮食偏好。
  模型（无命中）: 我没有关于你去过的地方的信息。

[4] 程序记忆：记「怎么做」，而不是「记了什么事实」（钩第九话 Skill）
```

同义改写命中（「喝咖啡吗」/「想戒掉什么」→「戒咖啡」，score 0.753/0.783）与 Python 版一致，是真实 Embedding 的价值。

## 说明

- 语义记忆与 Python 版一致：真实 bge-m3（1024 维）+ Qdrant 检索；Embedding/Qdrant 不可达时降级到纯 Java TF-IDF 兜底（只做回归，不冒充真实 Embedding）。
- 情节记忆用 `sqlite-jdbc`（`org.xerial:sqlite-jdbc:3.46.0.0`）落盘；**默认不关闭 journal**（保留崩溃安全）。只有在受限沙箱环境报 `SQLITE_IOERR_DELETE` 时，才通过环境变量 `MEMORY_SQLITE_UNSAFE_NO_JOURNAL=1` 显式关闭，仅限一次性实验。
