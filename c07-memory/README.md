# C07 演示（Java 21）：三层记忆——上下文、短期、长期

与 Python 版 `memory_demo.py` 同构的 Java 等价实现。

三层记忆：短期（WorkingMemory，会话内事实提取）+ 长期（纯 Java 余弦检索，中文 bigram + 停用词）。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export DEEPSEEK_API_KEY=sk-你的key
./gradlew :c07-memory:run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 预期输出（关键部分）

```
[1] 短期记忆：用户自报姓名，Agent 存下来
  → 短期记忆已存: 用户名字=张三

[2] 短期记忆：几轮之后，模型已经'忘了'用户名字——但记忆补上了
  模型(带记忆): 你是张三...
  模型(无记忆): 在没有上下文的情况下，我无法知道你是谁...

[3] 长期记忆：向量检索
  问『用户喜欢喝什么？』→ [用户喜欢喝茶，尤其是龙井]
  问『用户职业是什么？』→ [用户职业是 Java 后端工程师，擅长并发编程]
```

## 说明

- 纯 Java 实现余弦检索，无第三方向量库——演示目的是理解机制，生产用真实 embedding + Qdrant/Chroma
- 中文 bigram 分词对同义改写敏感（"爱喝"vs"喜欢喝"匹配不到），生产用 embedding 解决
