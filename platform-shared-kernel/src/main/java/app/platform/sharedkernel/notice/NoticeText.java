package app.platform.sharedkernel.notice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words of a notice (Sprint 25, ADR-0155): the platform's own text for each kind, the rules an administrator's text
 * must follow, and the insertion of facts into it. Pure logic, no database and no Spring, so the module that checks a
 * template when it is saved ({@code metadata}) and the module that builds the words when a notice is read
 * ({@code notification}) apply the same rules without depending on each other.
 *
 * <p>An administrator's text is text of a person and is treated as such: it is bounded, it holds no markup, no link and
 * no address, and the only way a fact enters it is a placeholder from a closed list per kind. A placeholder never
 * stands for a value of a record, only for a label of the organization, the object, the process, the step or the
 * workflow, cleaned by {@link #safeName(String)} when it is inserted.
 */
public final class NoticeText {

    /** Longest title. */
    public static final int TITLE_MAX = 80;

    /** Longest body. */
    public static final int BODY_MAX = 500;

    private static final int NAME_MAX = 80;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]{0,30})}");
    private static final Pattern BRACE = Pattern.compile("[{}]");
    private static final Pattern LINK = Pattern.compile("(?i)(https?:|ftp:|www\\.|://|@|\\bmailto)");

    /**
     * One thing wrong with a text.
     *
     * @param place {@code title} or {@code body}
     * @param message what is wrong, in words that never repeat the text
     */
    public record Problem(String place, String message) {
    }

    private NoticeText() {
    }

    /** The placeholders a text of this kind may use, in alphabetical order. */
    public static List<String> placeholders(NoticeKind kind) {
        TreeSet<String> names = new TreeSet<>(List.of("organization", "object"));
        kind.facts().stream().filter(fact -> !fact.equals("stage")).forEach(names::add);
        return List.copyOf(names);
    }

    /** What a placeholder stands for, in words, for the editor. */
    public static String describe(String placeholder) {
        return switch (placeholder) {
            case "organization" -> "The name of the organization.";
            case "object" -> "The label of the object the notice is about.";
            case "process" -> "The label of the approval process.";
            case "step" -> "The label of the step of the approval.";
            case "workflow" -> "The label of the workflow.";
            case "outcome" -> "How the approval ended: approved, rejected or withdrawn.";
            default -> "";
        };
    }

    /** The platform's own title for the kind. */
    public static String defaultTitle(NoticeKind kind) {
        return switch (kind) {
            case APPROVAL_TASK -> "A record needs your decision";
            case APPROVAL_REMINDER -> "Your decision is still needed";
            case APPROVAL_DECIDED -> "An approval has ended";
            case WORKFLOW_NOTICE -> "A record needs your attention";
            case TRIAL_NOTICE -> "About your trial";
        };
    }

    /** The platform's own body for the kind; the trial notice's words depend on the stage and are built elsewhere. */
    public static String defaultBody(NoticeKind kind) {
        return switch (kind) {
            case APPROVAL_TASK -> "A record of {object} waits for your decision in the approval \"{process}\".";
            case APPROVAL_REMINDER -> "A record of {object} still waits for your decision in the approval "
                    + "\"{process}\", step \"{step}\".";
            case APPROVAL_DECIDED -> "The approval \"{process}\" of your record of {object} ended: {outcome}.";
            case WORKFLOW_NOTICE -> "The workflow \"{workflow}\" asks you to look at a record of {object}.";
            case TRIAL_NOTICE -> "";
        };
    }

    /**
     * Checks a text an administrator wrote for a kind: lengths, no markup, no link, no address, no line break in the
     * title, and every placeholder one of the kind's list. Every problem is reported; none repeats the text.
     */
    public static List<Problem> validate(NoticeKind kind, String title, String body) {
        List<Problem> problems = new ArrayList<>();
        if (!kind.writable()) {
            problems.add(new Problem("kind", "The text of this kind of notice is the platform's own."));
            return problems;
        }
        check(kind, "title", title, TITLE_MAX, false, problems);
        check(kind, "body", body, BODY_MAX, true, problems);
        return problems;
    }

    private static void check(NoticeKind kind, String place, String text, int most, boolean lines,
            List<Problem> problems) {
        if (text == null || text.isBlank()) {
            problems.add(new Problem(place, "Write the " + place + "."));
            return;
        }
        if (text.length() > most) {
            problems.add(new Problem(place, "The " + place + " is at most " + most + " characters."));
        }
        if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0) {
            problems.add(new Problem(place, "The " + place + " is plain text: leave out the characters < and >."));
        }
        if (LINK.matcher(text).find()) {
            problems.add(new Problem(place, "The " + place + " holds no link and no address: the platform adds "
                    + "its own link."));
        }
        boolean badControl = text.chars().anyMatch(c -> Character.isISOControl(c) && !(lines && (c == '\n')));
        if (badControl) {
            problems.add(new Problem(place, "The " + place + (lines ? " may use line breaks and no other "
                    + "control characters." : " is one line.")));
        }
        List<String> allowed = placeholders(kind);
        Matcher found = PLACEHOLDER.matcher(text);
        while (found.find()) {
            if (!allowed.contains(found.group(1))) {
                problems.add(new Problem(place, "{" + found.group(1) + "} is not available here. Use "
                        + allowed.stream().map(name -> "{" + name + "}").toList() + "."));
            }
        }
        // A brace that is not part of a placeholder is a mistake in the text, not a character to print.
        String rest = PLACEHOLDER.matcher(text).replaceAll("");
        if (BRACE.matcher(rest).find()) {
            problems.add(new Problem(place, "A { or } that is not part of a placeholder; write a placeholder as "
                    + "{name}."));
        }
    }

    /**
     * Puts the facts into a text. A placeholder with no value is left out; every value is cleaned by
     * {@link #safeName(String)} first, so the result holds nothing a person's text could not.
     */
    public static String render(String text, Map<String, String> values) {
        Matcher found = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (found.find()) {
            String value = values.get(found.group(1));
            found.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : clean(value)));
        }
        found.appendTail(out);
        return out.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    /**
     * A label as it may appear in a notice or a mail: cut to a short length and reduced to letters, digits, spaces and
     * a few quiet marks. Anything that could make a link or an address (colon, slash, at-sign, dot) and anything that
     * could break a line is dropped, so the label can only be a name.
     */
    public static String safeName(String name) {
        String result = clean(name);
        return result.isEmpty() ? "an organization" : result;
    }

    private static String clean(String name) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length() && safe.length() < NAME_MAX; ) {
            int point = name.codePointAt(i);
            i += Character.charCount(point);
            if (Character.isLetterOrDigit(point) || " -'&,()".indexOf(point) >= 0) {
                safe.appendCodePoint(point);
            } else if (Character.isWhitespace(point)) {
                safe.append(' ');
            }
        }
        return safe.toString().strip().replaceAll(" {2,}", " ");
    }
}
