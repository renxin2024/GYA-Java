# C12 可恢复审批：Run 停了之后怎样安全地继续（Java 版）

上一话的结尾留了一个不好绕过去的问题：三种模式都能在当前进程里让 Run 停下来，进入等待用户确认。但一退出进程，内存里那条「等待审批」也跟着没了。第二天用户回来说「确认」，你凭什么找到对应的那份纲领、对应到哪个动作、恢复到哪个进度？

结论落在代码里：可恢复的 Human-in-the-Loop 不只是「把状态存进 SQLite」。系统要把审批绑定到一个不可变的待执行动作版本，恢复时同时校验「你批准的是不是这个动作」和「批准之后动作有没有被改过」，执行时靠业务幂等键不重复做事——缺一条都恢复不了。

> 本目录是 Java 实现（Java 21 + Gradle）。Python 实现在 [GYA 仓库](https://github.com/renxin2024/GYA/tree/main/c12-durable-resume) 的 `c12-durable-resume` 目录，博客正文的示例代码以那份为主。

## 目录结构

```
c12-durable-resume/
├── README.md           # 本文件
├── build.gradle.kts    # 子模块构建（application 插件 + sqlite-jdbc + JUnit 5 + Java 21 toolchain）
└── src/
    ├── main/java/cn/renxinblog/c12/Main.java       # Store：checkpoint + 审批绑定 + 幂等恢复 + Trace
    └── test/java/cn/renxinblog/c12/MainTest.java   # JUnit 测试（10 项）
```

Gradle wrapper 与 `settings.gradle.kts` 在仓库根目录，本子模块由根工程 `include("c12-durable-resume")` 纳入，不单独带 wrapper。

## 前置环境

| 项 | 要求 |
|---|---|
| JDK | 21（`toolchain` 已声明 `JavaLanguageVersion.of(21)`），`java -version` 应显示 `21.0.x` |
| Gradle | 用仓库根目录的 `./gradlew`，无需单独安装 |
| API Key | 不需要——`start` 确定性构造固定 proposal，不调 LLM |

> Java 侧必须用 JDK 21。本机默认 `java` 若已是 JDK 25，Gradle 自带的 Kotlin 脚本编译器会以
> `IllegalArgumentException: 25.0.3` 直接崩掉，与本文代码无关。

依赖 `org.xerial:sqlite-jdbc:3.46.0.0`，Gradle 从仓库镜像 / Maven Central 解析。

## 运行

在**仓库根目录**执行：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home  # 或你的 JDK 21 路径
./gradlew :c12-durable-resume:test        # JUnit 测试（10 项）
# output 统一用 /tmp/c12-draft.txt，与 Python 侧一致，保证两端 proposal_hash 相等
./gradlew :c12-durable-resume:run \
  --args="/tmp/c12-java.db /tmp/c12-draft.txt start run-1"
./gradlew :c12-durable-resume:run \
  --args="/tmp/c12-java.db /tmp/c12-draft.txt show-trace run-1"
# 从 JSON 输出复制 proposal_hash，再执行 approve 和 resume。
```

## 验收语义

- proposal 的 hash 覆盖 `action + args + output` 三者。固定 proposal（output 取 `/tmp/c12-draft.txt`）的 hash 是 `2bba1bb36ae552812b4b9e0d730249a3447adfc3ed100e0f4d92e3a8c28e7573`（Python / Java 两端一致）。
- 审批同时保存 `approval_request_id` 和 `approval_hash`；恢复时既比较当前 request ID，也重新计算 action/args/output 的 hash，并显式比对本次传入的 output 是否等于批准时冻结的 output。批准后替换 pending request、proposal 或 output 都会被拒绝。
- 正常完成后重复 `resume` 返回 `already_completed`，Trace 留 `action_replayed`。
- 测试在「文件已创建、COMPLETED 尚未写回」之间注入崩溃；重试发现相同幂等键对应的文件内容已存在时返回 `recovered`，不重写文件。
- 同一路径已存在但内容不同，恢复返回 `output_conflict`，不覆盖原文件。

## 已知边界

本 demo 只证明这个固定本地文件动作的幂等策略。SQLite 事务不能覆盖数据库之外的文件、邮件或支付；生产系统仍需业务幂等键、outbox/inbox 或目标系统提供的去重能力（这些属设计候选，本文未验证）。
