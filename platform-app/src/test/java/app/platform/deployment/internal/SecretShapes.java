package app.platform.deployment.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a secret looks like in a file (Sprint 33, ADR-0290). The platform keeps no secret in any file of the repository,
 * a values file, a script or a document; this recognises the shapes a careless paste leaves, so that CI fails before
 * the paste is pushed. It cannot know a secret it has no shape for: it is a net under the rule, not the rule.
 *
 * <p>A line that only <em>refers</em> to a secret (a variable, a secret store entry, a placeholder, a documentation
 * address) is not a finding; a line that holds a long random-looking value after a secret-like name is.
 */
final class SecretShapes {

    /** One finding: the rule that matched and the line (the matched text is never repeated). */
    record Finding(String rule, int line) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("private key block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new Rule("cloud access key identifier", Pattern.compile("\\b(AKIA|ASIA|AIDA|AROA)[0-9A-Z]{16}\\b")),
            new Rule("cloud secret access key", Pattern.compile(
                    "(?i)aws_?secret_?access_?key[\"']?\\s*[=:]\\s*[\"']?[A-Za-z0-9/+=]{40}\\b")),
            new Rule("token of a code host", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
            new Rule("token of a chat service", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
            new Rule("signed token", Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
            new Rule("a 32-byte key in Base64 after a name that says key", Pattern.compile(
                    "(?i)(kek|secret|signing|encryption)[a-z_\\-]*[\"']?\\s*[=:]\\s*[\"']?[A-Za-z0-9+/]{43}=")),
            new Rule("a long value after a secret-like name", Pattern.compile(
                    "(?i)(?<![a-z])(password|passwd|secret|token|api[_-]?key|private[_-]?key|credential)"
                            + "[a-z_\\-]*[\"']?\\s*[=:]\\s*[\"']?[A-Za-z0-9/+_\\-]{24,}[\"']?\\s*$")));

    /** Text that makes a line a reference and not a value. */
    private static final List<String> REFERENCE_MARKERS = List.of("${", "$(", "$env:", "$ENV", "var.", "valueFrom",
            "CHANGE-ME", "REPLACE", "example.test", "<", "{{", "secretsmanager:", "arn:", "random_password",
            "(redacted)", "throw-away", "generated", "never ", "PLATFORM_", "secret_arns", "local.");

    private record Rule(String name, Pattern pattern) {
    }

    private SecretShapes() {
    }

    /** The findings in a text, by rule and line number. */
    static List<Finding> scan(String text) {
        List<Finding> findings = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(line).find() && !isReference(rule, line)) {
                    findings.add(new Finding(rule.name(), i + 1));
                    break;
                }
            }
        }
        return findings;
    }

    private static boolean isReference(Rule rule, String line) {
        // A private key block or a cloud key identifier is never "a reference": only the value-after-a-name rules can
        // be.
        if (!rule.name().startsWith("a long value") && !rule.name().startsWith("a 32-byte")) {
            return false;
        }
        return REFERENCE_MARKERS.stream().anyMatch(line::contains);
    }
}
