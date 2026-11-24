package app.platform.deployment.internal;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The arithmetic of "is the database schema new enough for this release?" (ADR-0288). Pure: no database, no files.
 *
 * <p>A release carries migration files named {@code V<number>__<name>.sql}; the highest number is the schema version
 * the release needs. The database says which version it is at ({@code platform_schema_version()}). The rule of
 * expand and contract (ADR-0288) is what makes the three answers safe:
 * <ul>
 *   <li>{@link Verdict#CURRENT}: the database is at the version the release needs.</li>
 *   <li>{@link Verdict#AHEAD}: a newer release migrated the database and this one runs on it. This is the rollback
 *       case and is allowed, because every migration is backward compatible with the release before it.</li>
 *   <li>{@link Verdict#BEHIND}: the migration job has not run. The instance must not start: it would fail on the
 *       first statement that needs a table or a column the release expects, in the middle of a request.</li>
 * </ul>
 */
final class SchemaVersions {

    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d{1,6})__[A-Za-z0-9_]+\\.sql");

    /** How the database compares with the release. */
    enum Verdict {
        CURRENT, AHEAD, BEHIND
    }

    private SchemaVersions() {
    }

    /**
     * The schema version a release needs: the highest number among its migration files.
     *
     * @param fileNames the names (not paths) of the files of {@code db/migration}; other names are ignored
     * @return the highest version
     * @throws IllegalStateException when no file names a version (a release without migrations is a packaging fault)
     */
    static int needed(Collection<String> fileNames) {
        int highest = 0;
        for (String name : fileNames) {
            Matcher matcher = MIGRATION_FILE.matcher(name);
            if (matcher.matches()) {
                highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
            }
        }
        if (highest == 0) {
            throw new IllegalStateException(
                    "This release carries no migration files, so its schema version is unknown");
        }
        return highest;
    }

    static Verdict judge(int actual, int needed) {
        if (actual == needed) {
            return Verdict.CURRENT;
        }
        return actual > needed ? Verdict.AHEAD : Verdict.BEHIND;
    }

    /** The sentence an operator reads when the instance refuses to start. It names no host, user or statement. */
    static String behindMessage(int actual, int needed) {
        String where = actual == 0 ? "is empty or older than the version probe" : "is at version " + actual;
        return "The database schema is behind this release: it " + where + " and this release needs version " + needed
                + ". Run the migration job first (docs/runbooks/deploy.md), then start the application.";
    }
}
