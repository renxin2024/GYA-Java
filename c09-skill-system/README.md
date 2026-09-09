# C09 Skill 系统最小演示（Java 21）

这个 demo 与 Python 版验证同一条边界：**Host 不知道什么叫「Markdown 质量」，它只做发现、匹配、加载、按步骤执行**。真正的「检查什么、怎么检查」写在 Skill 自己的文件里——`SKILL.md` 声明步骤，`references/checklist.md` 声明规则，`Validator.java` 实现规则。它不模拟 LLM 推理。

## 职责拆在哪

| 谁 | 知道什么 | 不知道什么 |
|---|---|---|
| `Main.java`（Host） | 怎么发现 SKILL.md、读 frontmatter、匹配、按步骤执行 | 「Markdown 质量」具体指什么 |
| `skills/markdown-quality/SKILL.md` | 这个 Skill 叫什么、解决什么、步骤顺序 | 校验规则的实现细节 |
| `references/checklist.md` | 要检查哪些规则 | 怎么用代码去查 |
| `Validator.java` | 怎么用代码逐条校验 | 什么时候该用它、结果怎么上报 |

`SKILL.md` 的 frontmatter 里有一份 `steps` 列表：`ref:` 加载资源文件，`run: validate {file}` 声明一条校验动作。Host 读取这份 `steps` 并逐条执行——步骤从文件里来，不是写死在 Host 里。

## 运行

```bash
JAVA_HOME=<JDK 21 路径> ./gradlew :c09-skill-system:run
```

## 预期输出

```text
[discover] markdown-quality
[match] markdown-quality
[load] references/checklist.md
[run] validate .../samples/good.md
[validate] good.md -> PASS: frontmatter=ok h1=1 summary=ok
[run] validate .../samples/bad.md
[validate] bad.md -> FAIL: frontmatter must be delimited by ---; missing closing section: ## 总结
[validate] passed=1 failed=1 (expected)
[result] status=PASS
```

`status=PASS` 表示 Host 的验收条件被满足（合格样例通过、故意损坏的样例被拦截），不表示两份样例都合格。「验证器工作正常」和「被验证对象合格」是两回事。
