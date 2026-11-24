package app.platform.deployment.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the deployment files for the tests that hold them to their rules (Sprint 33). Paths are relative to platform-app. */
final class DeploymentFiles {

    static final Path DEPLOY = Path.of("../deploy");
    static final Path INFRA = DEPLOY.resolve("infra");
    static final Path VALUES = INFRA.resolve("values");
    static final Path ALERTS = DEPLOY.resolve("alerts/alerts.json");
    static final Path ALERTS_RUNBOOK = Path.of("../docs/runbooks/alerts.md");

    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s*=\\s*(.*?)\\s*$");

    private DeploymentFiles() {
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    /**
     * The assignments of a values file, comments removed, by name. A string loses its quotes; a number or a boolean is
     * kept as its text; a list is kept as written.
     */
    static Map<String, String> values(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : read(file).split("\\R")) {
            String code = withoutComment(line);
            Matcher match = ASSIGNMENT.matcher(code);
            if (match.matches()) {
                String value = match.group(2);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(match.group(1), value);
            }
        }
        return result;
    }

    /** A comment starts at a # that is not inside a string. */
    static String withoutComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    static int number(Map<String, String> values, String name) {
        String text = values.get(name);
        if (text == null) {
            throw new IllegalStateException("The values file has no " + name);
        }
        return Integer.parseInt(text);
    }
}
