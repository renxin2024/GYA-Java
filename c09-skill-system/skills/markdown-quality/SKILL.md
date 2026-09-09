---
name: markdown-quality
description: "Validate Markdown article structure before publication."
steps:
  - ref: references/checklist.md
  - run: validate {file}
---

# Markdown quality

Use this Skill when a user asks for a structural check of a Markdown article.

## 步骤

1. 先读 `references/checklist.md`，了解要检查哪些规则。
2. 对目标文件运行 `validate <markdown-file>`，得到确定性的检查结果。
3. 对每条失败的规则，报告文件路径和一条可操作的修复建议。
4. 未经用户明确要求，不要修改文章。

## 边界

`validate` 只回答「结构是否符合规则」，是确定性校验；「文章写得好不好」是语义判断，交给模型，不在这个 Skill 内。
