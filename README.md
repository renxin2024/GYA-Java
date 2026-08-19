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
| 构建 | **Gradle Wrapper 固定版本 8.14.2**（工程内自带 `gradlew`，无需预装 Gradle） |
| 包名 | `cn.renxinblog.*` |
| JSON | Jackson（`jackson-databind`，build.gradle.kts 声明） |

安装参考：
- macOS: `brew install openjdk@21`
- Ubuntu/Debian: `sudo apt install openjdk-21-jdk-headless`
- Windows: [Adoptium](https://adoptium.net)（JDK 21）

Gradle 无需预装——每个工程自带 `gradlew`，首次运行自动下载（默认走国内腾讯云镜像）。

## 国内下载源说明

| 用途 | 源 |
|------|-----|
| Gradle 发行版 | 腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/...`（gradle-wrapper.properties 已配置） |
| Maven 依赖 | 阿里云镜像 `https://maven.aliyun.com/repository/public` + `central`（build.gradle.kts 已配置，优先于 mavenCentral） |

无需科学上网。

## 目录导航

| 目录 | 对应文章 | 运行方式 | 状态 |
|------|---------|---------|------|
| `c01-chat-only/` | 01 只会说话的模型 | `./gradlew run`（交互式聊天） | ✅ 可运行 |
| `c02-function-calling/` | 02 Function Calling 第一性原理 | `./gradlew run` | ✅ 可运行 |
| `c03-model-training/` | 03 模型怎么被训出来的 | （规划中） | ⏳ |
| `c04-tool-registry/` | 04 工具注册表与 ToolResponse | （规划中） | ⏳ |
| ... | ... | ... | ... |

## 快速开始

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java/c01-chat-only
export DEEPSEEK_API_KEY=sk-你的key   # https://platform.deepseek.com 获取
./gradlew run
```

首次运行会从腾讯云镜像下载 Gradle 8.14.2 与依赖，稍等片刻即跑起来。

## 每篇目录内约定

```
c0X-xxx/                  # 独立 Gradle 工程
├── gradlew               # Gradle Wrapper（固定 8.14.2，国内镜像）
├── gradle/wrapper/       # wrapper 配置（distributionUrl 指向腾讯云）
├── settings.gradle.kts
├── build.gradle.kts      # application 插件 + Jackson(阿里云镜像) + Java 21 toolchain
└── src/main/java/
    └── cn/renxinblog/... # 主程序（cn.renxinblog 包 + 正式 JSON 库）
```

交互式 demo（如 C01 聊天）在 build.gradle.kts 里加了 `standardInput = System.in`，确保 `gradlew run` 能接收终端输入。

## 许可

MIT