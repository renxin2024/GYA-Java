# C09 Skill 系统最小演示（Java 21）

这个 demo 与 Python 版验证同一条边界：发现 Skill、根据描述匹配、按需加载正文与引用文件，再由确定性校验器检查一个成功样例和一个失败样例。它不模拟 LLM 推理。

```bash
./gradlew :c09-skill-system:run
```

跑通标准：最后输出 `[result] status=PASS`，并且 `good.md` 通过、`bad.md` 按预期失败。
