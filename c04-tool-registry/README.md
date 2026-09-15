# C04 演示（Java 21）：工具注册表与 ToolResponse

对应 Python 版 `registry.py`。零第三方依赖，不需要 API Key，也不发起网络调用。

## 运行

```bash
git clone git@github.com:renxin2024/GYA-Java.git
cd GYA-Java
./gradlew :c04-tool-registry:run
```

首次运行自动从腾讯云镜像下载 Gradle 8.14.2、从阿里云镜像拉依赖（无需科学上网）。

## 通过标准

看到 `ALL_TESTS_PASSED` 即通过。它与 Python 版跑的是同一组七类断言：未知工具、工具下线、参数错误、其他执行失败、坏更新保留健康旧版、超时已成功回放、超时未执行重试一次。

## 说明

- `src/main/java/cn/renxinblog/c04/ToolRegistry.java`：本演示实现
- `legacy/Main.java`：早期版本，与文章不对应，仅供追溯（其 README 同放于 `legacy/`）
