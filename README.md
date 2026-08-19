# GYA-Java — Get Your Agent（Java 版）

**GYA（Get Your Agent）** 系列文章的 **Java 21 + Gradle 版**配套代码仓库。

## 这个仓库是什么

与 [GYA](https://github.com/renxin2024/GYA)（Python 版）对应，本仓库存放系列每篇文章的 **Java 21 等价实现**。

系列以「拥有自己的 Agent」为最终承诺，从「模型只会说话」讲起，一步步拆解 Agent 能力的每次跃迁——Function Calling、Agent 循环、MCP 协议、Skill 系统、多 Agent 协作——每篇文章都配一个能直接跑起来的最小演示。

> 系列文章发布在 https://www.renxinblog.cn/series/gya/
> 文章正文的示例代码以 Python 为主；偏好 Java 的读者用本仓库的等价实现。

## 版本要求

| 项 | 要求 |
|----|------|
| JDK | 21（LTS）—— `java -version` 显示 `21.0.x` |
| 构建 | **Gradle Wrapper 固定版本 8.14.2**（仓库根目录 `gradlew`，无需预装 Gradle） |
| 包名 | `cn.renxinblog.*` |
| JSON | Jackson（`jackson-databind`，build.gradle.kts 声明） |

安装参考：
- macOS: `brew install openjdk@21`
- Ubuntu/Debian: `sudo apt install openjdk-21-jdk-headless`
- Windows: [Adoptium](https://adoptium.net)（JDK 21）

Gradle 无需预装——仓库自带 `gradlew`，首次运行自动下载（默认走国内腾讯云镜像）。

## 国内下载源说明

| 用途 | 源 |
|------|-----|
| Gradle 发行版 | 腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/...`（gradle-wrapper.properties 已配置） |
| Maven 依赖 | 阿里云镜像 `https://maven.aliyun.com/repository/public` + `central`（根 build.gradle.kts 的 allprojects 统一配置，优先于 mavenCentral） |

无需科学上网。

## 目录导航（Gradle 多模块工程）

```
GYA-Java/
├── gradlew / gradle/wrapper/      # 根目录唯一一份 Wrapper（固定 8.14.2，腾讯云镜像）
├── settings.gradle.kts            # include 全部子模块
├── build.gradle.kts               # 根配置：统一仓库镜像
├── .gitignore                     # 忽略 .gradle/ build/ 等本地产物
├── c01-chat-only/                 # 子模块：只会说话的模型
│   ├── build.gradle.kts             # application 插件 + mainClass + Jackson
│   └── src/main/java/cn/renxinblog/c01/Chat.java
├── c02-function-calling/          # 子模块：Function Calling 第一性原理
│   ├── build.gradle.kts
│   └── src/main/java/cn/renxinblog/c02/Main.java
└── ...                            # 后续篇目按同结构追加
```

## 快速开始

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
export DEEPSEEK_API_KEY=sk-你的key   # https://platform.deepseek.com 获取

# C01：只会说话的模型（交互式聊天）
./gradlew :c01-chat-only:run

# C02：Function Calling 完整闭环
./gradlew :c02-function-calling:run
```

首次运行会从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖，稍等片刻即跑起来。

## 新增一篇的约定

1. 新建子模块目录 `c0X-xxx/`：`build.gradle.kts`（application 插件 + 依赖 + mainClass）+ `src/main/java/cn/renxinblog/xxx/`
2. `settings.gradle.kts` 里 `include("c0X-xxx")`
3. 交互式 demo 在子模块 build.gradle.kts 加 `tasks.withType<JavaExec> { standardInput = System.in }`，否则 `./gradlew` 收不到 stdin

## 许可

MIT