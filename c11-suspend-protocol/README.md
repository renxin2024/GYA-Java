# C11 暂停协议：模型只提决定，Runtime 才让 Run 停下来（Java 版）

同一个技术文章创作任务跑三种等待信号来源（ReAct / Plan-and-Execute / Workflow），
看一件事：**模型说了「请用户确认」以后，程序为什么真的会停下来？**

结论落在代码里：模型不能暂停程序。模型只能提出候选决定；
`Runtime` 负责解析它、校验当前状态允不允许它、执行状态迁移，
再告诉调度器「继续、挂起、完成还是失败」。

> 本目录是 Java 实现（Java 21 + Gradle）。Python 实现在 [GYA 仓库](https://github.com/renxin2024/GYA/tree/main/c11-suspend-protocol) 的 `c11-suspend-protocol` 目录，博客正文的示例代码以那份为主。
> 真实模型实验只在 Python 侧执行，本目录只有离线夹具——这是两种语言之间已知的不对称项。

## 目录结构

```
c11-suspend-protocol/
├── README.md           # 本文件
├── build.gradle.kts    # 子模块构建（application 插件 + JUnit 5 + Java 21 toolchain）
└── src/
    ├── main/java/cn/renxinblog/c11/Main.java       # 协议类型 + Runtime + ActionGateway + 三种控制器 + 缺陷版循环
    └── test/java/cn/renxinblog/c11/MainTest.java   # JUnit 测试（20 项）
```

Gradle wrapper 与 `settings.gradle.kts` 在仓库根目录，本子模块由根工程 `include("c11-suspend-protocol")` 纳入，不单独带 wrapper。

## 四层职责

| 层次 | 职责 | 代码落点 |
|---|---|---|
| 模型 | 根据上下文提出动作、结束或请求用户输入 | `ReactController` / `PlannerController` / `WorkflowController` 的夹具输出 |
| Runtime | 解析模型输出，校验状态前置条件，决定状态迁移 | `Runtime.handle()` |
| Scheduler | 按迁移结果决定继续循环还是退出当前 Run | `runWithRuntime()` |
| UI / Continuation | 展示问题、接收用户输入、创建下一次执行 | 本 demo 只到「返回调用方」为止（C12） |

`Controller.propose()` 只提出决定；只有 `Runtime.handle()` 拥有状态迁移权。

## 三种来源的一句话区别

| 来源 | 等待信号从哪来 | 它**没有**做什么 |
|---|---|---|
| ReAct | 模型在观察后自己提出 `REQUEST_USER_INPUT` | 不改变 Run 状态，也不停止循环 |
| Plan-and-Execute | 计划里的一步，执行器照原样交给 Runtime | 计划文本本身没有暂停能力 |
| Workflow | 代码里的固定节点，到达即提出 | 不决定状态迁移，迁移仍由 Runtime 做 |

三者都汇聚到同一个入口：`ControlDecision → Runtime → RunStatus → Scheduler`。

## 前置环境

| 项 | 要求 |
|---|---|
| JDK | 21（`toolchain` 已声明 `JavaLanguageVersion.of(21)`），`java -version` 应显示 `21.0.x` |
| Gradle | 用仓库根目录的 `./gradlew`，无需单独安装 |
| API Key | 不需要——本目录只跑离线夹具 |

> Java 侧必须用 JDK 21。本机默认 `java` 若已是 JDK 25，Gradle 自带的 Kotlin 脚本编译器会以
> `IllegalArgumentException: 25.0.3` 直接崩掉，与本文代码无关。

## 运行

在**仓库根目录**执行：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home  # 或你的 JDK 21 路径
./gradlew :c11-suspend-protocol:test                        # JUnit 测试（20 项）
./gradlew :c11-suspend-protocol:run                         # 三种来源正常跑：全部停在同一边界
./gradlew :c11-suspend-protocol:run --args="--naive"        # 缺陷版：没有 Runtime 的循环
./gradlew :c11-suspend-protocol:run --args="--no-follow-up" # 关闭线索，验证 Workflow 是真条件分支
```

## 它证明的事

1. **模型输出只是数据，不是控制流。** 自然语言「请用户确认」在 `Runtime.parse()` 处就会失败——它不是机器可识别的等待信号。
2. **`waiting=true` 不等于循环已经停止。** 缺陷版把等待记成一个布尔标记后继续跑：ReAct 那一路一直转到预算耗尽；Plan-and-Execute 那一路甚至跨过等待点去尝试 `write_draft`，直到被 Gateway 拦下。
3. **Gateway 拦截不等于 Run 暂停。** Gateway 回答「这个动作能不能执行」，Runtime 回答「当前 Run 要不要继续」。被拦住时状态仍是 `RUNNING`，也没有 pending request。
4. **非法状态下的等待信号会被拒绝。** 还没有 `create_brief` 就提出等待、或者等待不带问题，都会被拒绝（前者让 Run 明确失败，不留下 pending）。
5. **「停下」是可验证的，不是自述的。** 判据 `isRunSuspended()` 要求四件事同时成立：状态是 `WAITING_FOR_USER`、有 pending request、等待后模型调用增量为 0、工具尝试增量为 0。缺陷版会在这条判据上失败——这正是测试抓住它的方式。
6. **解析失败不留活路。** Runtime 无法解释的输出不会「当成正常动作混过去」，而是让 Run 以明确失败结束。
7. **三种来源只是信号产生方式不同。** 它们的等待事件序列完全一致：`signal_received → runtime_validated → state_transition → pending_recorded → run_exited`。

本目录的输出与 Python 侧**逐字段一致**（正确版与缺陷版都是），对应的落地日志在 [GYA 仓库](https://github.com/renxin2024/GYA/tree/main/c11-suspend-protocol) 的 `traces/` 目录。

## 已知边界（C11 只做到这里）

本 demo 不实现、也不声称已经实现：数据库持久化；进程重启后恢复；多实例恢复；
审批结果与具体动作绑定；重复审批幂等；超时与取消；恢复后避免重复副作用。
这些属于 C12：当前 Run 停下来之后，怎样找到原进度并安全地继续。

## 常见坑

1. **`IllegalArgumentException: 25.0.3`**：Gradle 自带的 Kotlin 脚本编译器不认 JDK 25，把 `JAVA_HOME` 指到 JDK 21 即可。
2. **`UnsupportedClassVersionError`**：同样确认 `JAVA_HOME` 指向 JDK 21（不是 17 或 8）。
3. **子模块命令找不到**：`./gradlew :c11-suspend-protocol:run` 必须在仓库根目录执行；进到子模块目录没有 wrapper。
