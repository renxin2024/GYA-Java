# C04 实验（Java 21）：工具注册表与 ToolResponse

与 [GYA Python C04](https://github.com/renxin2024/GYA/tree/main/c04-tool-registry) 对照的本地确定性实验：工具缺失、工具下线、参数错误、执行失败、幂等查询与超时恢复使用同一组错误码和 Trace 语义。

它不调用 LLM、订单或支付系统，也不需要 API Key。`RefundDomain` 仅模拟领域幂等状态，用于划分 Runtime 与领域 handler 的职责。

## 运行

环境：JDK 21；无第三方业务依赖。

```bash
./gradlew :c04-tool-registry:run
```

通过标准：输出 `ALL_TESTS_PASSED`。其中“超时但领域已成功”的断言确保 `REPLAY_SUCCESS` 不会触发第二次退款执行。
