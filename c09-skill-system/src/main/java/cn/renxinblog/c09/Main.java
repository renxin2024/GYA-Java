package cn.renxinblog.c09;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** C09: a tiny deterministic Skill host, equivalent to the Python demo. */
public final class Main {
    private static final Path ROOT = locateRoot();
    private static final Path SKILLS = ROOT.resolve("skills");
    private static final Path SAMPLES = ROOT.resolve("samples");

    private static Path locateRoot() {
        Path module = Path.of("c09-skill-system");
        return Files.isDirectory(module) ? module : Path.of(".");
    }

    private static Map<String, String> readFrontmatter(Path skillFile) throws IOException {
        String text = Files.readString(skillFile);
        if (!text.startsWith("---\n")) throw new IllegalArgumentException("missing frontmatter: " + skillFile);
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("unterminated frontmatter: " + skillFile);
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : text.substring(4, end).split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) metadata.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim().replace("\"", ""));
        }
        if (!metadata.containsKey("name") || !metadata.containsKey("description")) {
            throw new IllegalArgumentException("name and description are required: " + skillFile);
        }
        return metadata;
    }

    private static String validate(Path sample) throws IOException {
        String text = Files.readString(sample);
        var errors = new java.util.ArrayList<String>();
        if (!text.startsWith("---\n") || text.indexOf("\n---\n", 4) < 0) errors.add("frontmatter must be delimited by ---");
        Matcher headings = Pattern.compile("(?m)^# ").matcher(text);
        int h1 = 0;
        while (headings.find()) h1++;
        if (h1 != 1) errors.add("expected exactly one H1");
        if (!text.contains("## 总结")) errors.add("missing closing section: ## 总结");
        return errors.isEmpty() ? "PASS: frontmatter=ok h1=1 summary=ok" : "FAIL: " + String.join("; ", errors);
    }

    public static void main(String[] args) throws Exception {
        String request = "请检查这篇 Markdown 文章的格式";
        Path selected = null;
        try (var paths = Files.list(SKILLS)) {
            for (Path directory : paths.sorted().toList()) {
                Path skillFile = directory.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) continue;
                Map<String, String> metadata = readFrontmatter(skillFile);
                System.out.println("[discover] " + metadata.get("name"));
                if (request.toLowerCase().contains("markdown") || metadata.get("description").toLowerCase().contains("markdown")) selected = skillFile;
            }
        }
        if (selected == null) throw new IllegalStateException("[result] status=NO_MATCH");
        Map<String, String> metadata = readFrontmatter(selected);
        System.out.println("[match] " + metadata.get("name"));
        String skillText = Files.readString(selected);
        if (!skillText.contains("references/checklist.md")) throw new IllegalStateException("[result] status=INVALID_SKILL");
        System.out.println("[load] SKILL.md + references/checklist.md");
        int passed = 0, failed = 0;
        for (String name : new String[]{"good.md", "bad.md"}) {
            String result = validate(SAMPLES.resolve(name));
            if (result.startsWith("PASS")) passed++; else failed++;
            System.out.println("[validate] " + name + " -> " + result);
        }
        if (passed != 1 || failed != 1) throw new IllegalStateException("[result] status=FAIL");
        System.out.println("[validate] passed=1 failed=1 (expected)");
        System.out.println("[result] status=PASS");
    }
}
