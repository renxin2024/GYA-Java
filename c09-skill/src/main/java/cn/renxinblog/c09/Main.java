package cn.renxinblog.c09;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Main {
    public static void main(String[] args) throws Exception {
        Path skillPath = Path.of("../GYA/c09-skill/skills/release-note/SKILL.md").toAbsolutePath();
        if (!Files.exists(skillPath)) skillPath = Path.of("skills/release-note/SKILL.md");
        Map<String, String> skill = load(skillPath);
        System.out.println("[1] 发现 Skill: " + skill.get("name") + " v" + skill.get("version"));
        System.out.println("[2] 加载指令: " + skill.get("instructions").length() + " 字符");
        System.out.println("[3] 执行结果:");
        System.out.println(skill.get("instructions"));
        System.out.println("任务：总结本次小版本的变更");
        System.out.println("结果：先列出变更，再列出验证证据。");
    }

    static Map<String, String> load(Path path) throws Exception {
        String text = Files.readString(path);
        String[] parts = text.split("---\\n", 3);
        if (parts.length < 3) throw new IllegalArgumentException("invalid Skill front matter");
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : parts[1].split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        values.put("instructions", parts[2].trim());
        return values;
    }
}
