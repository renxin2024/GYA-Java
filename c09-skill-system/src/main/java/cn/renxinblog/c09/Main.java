package cn.renxinblog.c09;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C09: a tiny Skill host that executes the steps declared in SKILL.md.
 *
 * The host itself knows nothing about "markdown quality". It only knows how to
 * discover SKILL.md files, read their frontmatter (name / description / steps),
 * match a request against a description, and execute the declared steps in
 * order. The concrete "what to check" lives in {@link Validator}, which is only
 * reached because the SKILL.md declares a {@code run: validate {file}} step.
 */
public final class Main {
    private static final Path ROOT = locateRoot();
    private static final Path SKILLS = ROOT.resolve("skills");
    private static final Path SAMPLES = ROOT.resolve("samples");

    private static Path locateRoot() {
        Path module = Path.of("c09-skill-system");
        return Files.isDirectory(module) ? module : Path.of(".");
    }

    /** A parsed skill: its frontmatter plus the ordered steps it declares. */
    private record Skill(Path dir, Map<String, String> metadata, List<Step> steps) {
    }

    private record Step(String kind, String value) {
    }

    private static Skill readSkill(Path skillFile) throws IOException {
        String text = Files.readString(skillFile);
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("missing frontmatter: " + skillFile);
        }
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated frontmatter: " + skillFile);
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        List<Step> steps = new ArrayList<>();
        for (String line : text.substring(4, end).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("- ref:")) {
                steps.add(new Step("ref", trimmed.substring("- ref:".length()).trim()));
            } else if (trimmed.startsWith("- run:")) {
                steps.add(new Step("run", trimmed.substring("- run:".length()).trim()));
            } else {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    if (key.equals("name") || key.equals("description")) {
                        metadata.put(key, line.substring(colon + 1).trim().replace("\"", ""));
                    }
                }
            }
        }
        if (!metadata.containsKey("name") || !metadata.containsKey("description")) {
            throw new IllegalArgumentException("name and description are required: " + skillFile);
        }
        return new Skill(skillFile.getParent(), metadata, steps);
    }

    private static boolean matches(String description, String request) {
        String desc = description.toLowerCase();
        String req = request.toLowerCase();
        return desc.contains("markdown") && req.contains("markdown");
    }

    public static void main(String[] args) throws Exception {
        String request = "请检查这篇 Markdown 文章的格式";

        // 1. discover: read only each skill's frontmatter
        Skill selected = null;
        List<Skill> discovered = new ArrayList<>();
        try (var paths = Files.list(SKILLS)) {
            for (Path directory : paths.sorted().toList()) {
                Path skillFile = directory.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                Skill skill = readSkill(skillFile);
                discovered.add(skill);
                System.out.println("[discover] " + skill.metadata().get("name"));
            }
        }

        // 2. match: pick the skill whose description overlaps the request
        for (Skill skill : discovered) {
            if (matches(skill.metadata().get("description"), request)) {
                selected = skill;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("[result] status=NO_MATCH");
        }
        System.out.println("[match] " + selected.metadata().get("name"));

        // 3. load + execute: run the steps the skill itself declares, in order
        String runCommand = null;
        for (Step step : selected.steps()) {
            if (step.kind().equals("ref")) {
                System.out.println("[load] " + step.value());
            } else if (step.kind().equals("run")) {
                runCommand = step.value();
            }
        }

        // 4. validate: the run step names the action; the host dispatches to it
        if (runCommand == null || !runCommand.startsWith("validate ")) {
            throw new IllegalStateException("[result] status=INVALID_SKILL");
        }

        int passed = 0;
        int failed = 0;
        for (String name : new String[]{"good.md", "bad.md"}) {
            Path sample = SAMPLES.resolve(name);
            System.out.println("[run] validate " + sample);
            String result = Validator.validate(sample);
            if (result.startsWith("PASS")) {
                passed++;
            } else {
                failed++;
            }
            System.out.println("[validate] " + name + " -> " + result);
        }
        if (passed != 1 || failed != 1) {
            throw new IllegalStateException("[result] status=FAIL");
        }
        System.out.println("[validate] passed=1 failed=1 (expected)");
        System.out.println("[result] status=PASS");
    }
}
