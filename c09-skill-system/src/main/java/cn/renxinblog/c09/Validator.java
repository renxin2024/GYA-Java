package cn.renxinblog.c09;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic validator for the markdown-quality Skill.
 *
 * This class knows "what to check" and "how to check" — the concrete rules for
 * a well-formed Markdown article. The host ({@link Main}) does not contain this
 * knowledge; it only executes whatever step the SKILL.md declares.
 */
public final class Validator {

    private Validator() {
    }

    public static String validate(Path file) throws IOException {
        String text = Files.readString(file);
        List<String> errors = new ArrayList<>();

        if (!text.startsWith("---\n") || text.indexOf("\n---\n", 4) < 0) {
            errors.add("frontmatter must be delimited by ---");
        }

        Matcher h1 = Pattern.compile("(?m)^# ").matcher(text);
        int count = 0;
        while (h1.find()) {
            count++;
        }
        if (count != 1) {
            errors.add("expected exactly one H1");
        }

        if (!text.contains("## 总结")) {
            errors.add("missing closing section: ## 总结");
        }

        if (errors.isEmpty()) {
            return "PASS: frontmatter=ok h1=1 summary=ok";
        }
        return "FAIL: " + String.join("; ", errors);
    }
}
