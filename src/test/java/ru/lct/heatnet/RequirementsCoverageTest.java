package ru.lct.heatnet;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry of requirements (docs/requirements.md) stays connected to the code: every requirement has a check,
 * and every check it names exists — a rule of the output validator, a test method, a section of docs/metrics.md.
 */
class RequirementsCoverageTest {

    private static final Pattern ROW = Pattern.compile("^\\| (R-[A-Z]+-\\d+) \\|(.*)\\|\\s*$");
    private static final Pattern CHECK = Pattern.compile("`(V|T|M):([^`]+)`");

    @Test
    void everyRequirementHasAnExistingCheck() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("docs", "requirements.md"), StandardCharsets.UTF_8);
        String validator = read(Paths.get("src", "main", "java", "ru", "lct", "heatnet", "validate",
                "OutputValidator.java"));
        String metrics = read(Paths.get("docs", "metrics.md"));
        Set<String> ids = new HashSet<>();
        List<String> problems = new ArrayList<>();
        int rows = 0;
        for (String line : lines) {
            Matcher m = ROW.matcher(line);
            if (!m.matches()) {
                continue;
            }
            rows++;
            String id = m.group(1);
            if (!ids.add(id)) {
                problems.add(id + ": duplicate id");
            }
            String[] cells = m.group(2).split("\\|", -1);
            if (cells.length != 6) {
                problems.add(id + ": expected 7 columns, got " + (cells.length + 1));
                continue;
            }
            String cls = cells[2].trim();
            String check = cells[4];
            if (!Stream.of("норма", "ответ", "трактовка", "данные", "вне").anyMatch(cls::equals)) {
                problems.add(id + ": unknown class '" + cls + "'");
            }
            Matcher c = CHECK.matcher(check);
            boolean any = check.contains("`вручную:");
            while (c.find()) {
                any = true;
                String kind = c.group(1);
                String target = c.group(2).trim();
                if ("V".equals(kind) && !validator.contains("\"" + target + "\"")) {
                    problems.add(id + ": OutputValidator has no rule " + target);
                } else if ("T".equals(kind) && !testExists(target)) {
                    problems.add(id + ": no test " + target);
                } else if ("M".equals(kind) && !Pattern.compile("(?m)^#{2,3} .*" + Pattern.quote(target))
                        .matcher(metrics).find()) {
                    problems.add(id + ": docs/metrics.md has no section '" + target + "'");
                }
            }
            if (!any) {
                problems.add(id + ": no check");
            }
        }
        assertTrue(rows > 50, "registry rows found: " + rows);
        assertEquals(new ArrayList<String>(), problems);
    }

    private static boolean testExists(String target) throws Exception {
        String cls = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
        String method = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;
        try (Stream<Path> files = Files.walk(Paths.get("src", "test", "java"))) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().equals(cls + ".java")) {
                    return method == null || Pattern.compile("void " + Pattern.quote(method) + "\\(")
                            .matcher(read(p)).find();
                }
            }
        }
        return false;
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
