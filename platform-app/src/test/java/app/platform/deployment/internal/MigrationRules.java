package app.platform.deployment.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deploy rule about migrations (ADR-0288), as something a machine can check: a migration that holds a heavy lock on
 * a table that already holds data, or that breaks the release before it, is a risk to a release made with live traffic.
 * Every migration runs in the job before the new release starts, but the old release is still serving while it runs.
 *
 * <p>The check reads the text of a file, so it is a net and not a proof: it flags the statements that are known to need
 * care. A file that has a good reason carries the marker line {@code -- long-lock-reviewed: <the reason>} (at least ten
 * characters), which says that a person looked at the cost on a big table and what was done about it (a batch, an
 * off-peak window, the table is small by construction). A table that the same file creates is empty by definition, so
 * the statements about it are not flagged.
 *
 * <p>What is flagged, and the safe way round it:
 * <ul>
 *   <li>a column type change, {@code set not null}, a volatile default on a new column, an update or delete with no
 *       {@code where}: each rewrites or scans the table under a lock (add the column nullable, fill in batches,
 *       validate a {@code not valid} check constraint);</li>
 *   <li>{@code create index} without {@code concurrently}, a foreign key or check constraint that is not
 *       {@code not valid}: each blocks writes while it builds or checks;</li>
 *   <li>{@code drop table}, {@code drop column}, a rename: these break the release before this one (contract steps; they
 *       belong to a later release than the one that stopped using the thing);</li>
 *   <li>{@code lock table}, {@code truncate}, {@code vacuum full}, {@code cluster}, a plain {@code reindex}.</li>
 * </ul>
 */
final class MigrationRules {

    private static final Pattern MARKER = Pattern.compile("--[ \\t]*long-lock-reviewed:[ \\t]*(\\S[^\\n]{9,})");
    private static final Pattern DOLLAR_QUOTED = Pattern.compile("\\$([A-Za-z_]*)\\$.*?\\$\\1\\$", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^create\\s+(?:unlogged\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z0-9_.\"]+)");
    private static final Pattern ALTER_TABLE = Pattern.compile("^alter\\s+table\\s+(?:only\\s+)?(?:if\\s+exists\\s+)?"
            + "([a-z0-9_.\"]+)");
    private static final Pattern CREATE_INDEX = Pattern.compile("^create\\s+(?:unique\\s+)?index\\s+(?!concurrently)"
            + "(?:if\\s+not\\s+exists\\s+)?[a-z0-9_.\"]+\\s+on\\s+(?:only\\s+)?([a-z0-9_.\"]+)");
    private static final Pattern UPDATE = Pattern.compile("^update\\s+(?:only\\s+)?([a-z0-9_.\"]+)\\s+set\\b");
    private static final Pattern DELETE = Pattern.compile("^delete\\s+from\\s+(?:only\\s+)?([a-z0-9_.\"]+)");
    private static final Pattern VOLATILE_DEFAULT = Pattern.compile(
            "\\badd\\s+column\\b.*\\bdefault\\b.*\\b(random|gen_random_uuid|uuidv7|uuid_generate_v\\d|clock_timestamp|"
                    + "nextval|timeofday)\\s*\\(");

    private MigrationRules() {
    }

    /** One thing a person must look at. */
    record Finding(String statement, String reason) {
    }

    /** Whether the file carries the marker of a reviewed long lock, with a reason. */
    static boolean isReviewed(String sql) {
        return MARKER.matcher(sql).find();
    }

    /** The statements of the file that need care, or none. A reviewed file has none by definition. */
    static List<Finding> check(String sql) {
        if (isReviewed(sql)) {
            return List.of();
        }
        List<String> statements = statements(sql);
        Set<String> created = new HashSet<>();
        for (String statement : statements) {
            Matcher match = CREATE_TABLE.matcher(statement);
            if (match.find()) {
                created.add(unquote(match.group(1)));
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (String statement : statements) {
            String reason = reasonFor(statement, created);
            if (reason != null) {
                findings.add(new Finding(shorten(statement), reason));
            }
        }
        return findings;
    }

    private static String reasonFor(String s, Set<String> created) {
        Matcher alter = ALTER_TABLE.matcher(s);
        if (alter.find()) {
            return alterReason(s, unquote(alter.group(1)), created);
        }
        Matcher index = CREATE_INDEX.matcher(s);
        if (index.find() && !created.contains(unquote(index.group(1)))) {
            return "create index without concurrently blocks writes while it builds; use create index concurrently";
        }
        Matcher update = UPDATE.matcher(s);
        if (update.find() && !created.contains(unquote(update.group(1))) && !s.contains(" where ")) {
            return "an update with no where rewrites every row under a lock; do it in batches";
        }
        Matcher delete = DELETE.matcher(s);
        if (delete.find() && !created.contains(unquote(delete.group(1))) && !s.contains(" where ")) {
            return "a delete with no where removes every row under a lock; do it in batches";
        }
        if (s.matches("^drop\\s+table\\b.*")) {
            return "drop table breaks the release before this one; a contract step belongs to a later release";
        }
        if (s.matches("^(lock\\s+table|truncate|vacuum\\s+full|cluster)\\b.*")) {
            return "this statement takes a heavy lock on a table";
        }
        if (s.matches("^reindex\\b(?!.*concurrently).*")) {
            return "reindex without concurrently blocks writes";
        }
        return null;
    }

    private static String alterReason(String s, String table, Set<String> created) {
        if (created.contains(table)) {
            return null;
        }
        if (s.matches(".*\\bdrop\\s+column\\b.*")) {
            return "drop column breaks the release before this one; a contract step belongs to a later release";
        }
        if (s.matches(".*\\brename\\b.*")) {
            return "a rename breaks the release before this one; add the new name, move, remove the old one later";
        }
        if (s.matches(".*\\balter\\s+column\\b.*\\b(set\\s+data\\s+)?type\\b.*")) {
            return "a column type change rewrites the table under a lock";
        }
        if (s.matches(".*\\bset\\s+not\\s+null\\b.*")) {
            return "set not null scans the whole table under a lock; add a check constraint not valid, validate it, "
                    + "then set not null";
        }
        if (VOLATILE_DEFAULT.matcher(s).find()) {
            return "a volatile default on a new column rewrites the table; add it nullable and fill it in batches";
        }
        boolean addsCheck = s.matches(".*\\badd\\s+(constraint\\s+\\S+\\s+)?(foreign\\s+key|check)\\b.*");
        if (addsCheck && !s.contains("not valid")) {
            return "a foreign key or check constraint that is not 'not valid' blocks writes while it checks; add it "
                    + "not valid, then validate";
        }
        return null;
    }

    /** The statements, without comments, string contents or function bodies. */
    static List<String> statements(String sql) {
        String text = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        text = LINE_COMMENT.matcher(text).replaceAll(" ");
        text = DOLLAR_QUOTED.matcher(text).replaceAll("''");
        text = STRING.matcher(text).replaceAll("''");
        List<String> result = new ArrayList<>();
        for (String part : text.split(";")) {
            String normal = part.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            if (!normal.isEmpty()) {
                result.add(normal);
            }
        }
        return result;
    }

    private static String unquote(String name) {
        String bare = name.replace("\"", "");
        int dot = bare.lastIndexOf('.');
        return dot >= 0 ? bare.substring(dot + 1) : bare;
    }

    private static String shorten(String statement) {
        return statement.length() <= 100 ? statement : statement.substring(0, 100) + "...";
    }
}
