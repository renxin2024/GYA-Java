# C03：模型为什么能生成工具调用请求？

Java 21 demo 用虚构订单 fixture 展示 Runtime 注入事实、候选工具调用和业务执行权的边界；程序不会执行退款、取消或订单查询。

```bash
./gradlew :c03-function-calling-training:run
./gradlew :c03-function-calling-training:run --args='--swapped'
```

`--swapped` 只对调工具 description，用于故障注入观察，不是生产配置。
