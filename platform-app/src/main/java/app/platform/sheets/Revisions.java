package app.platform.sheets;

import java.util.Locale;

/**
 * How a version of a sheet is spoken of to people (FA7, ADR-0201): as a <em>revision</em>, written with two digits and
 * starting at 00. The stored number is not changed and not renumbered: the first version a sheet has is stored as 1 and
 * shown as Revision 00, the next as Revision 01. The API keeps its field names and numbers; only the words that people
 * read go through here. The same rule is written in the browser ({@code lib/sheets/revision.ts}) with the same cases.
 *
 * <p>Pure: no record, no table, no text of an organization.
 */
public final class Revisions {

    private Revisions() {
    }

    /** The revision as people read it: the stored number minus one, at least two digits (1 gives 00, 2 gives 01). */
    public static String show(int stored) {
        return String.format(Locale.ROOT, "%02d", Math.max(0, stored - 1));
    }

    /** {@code Revision 00}: the words that name a version in a sentence. */
    public static String words(int stored) {
        return "Revision " + show(stored);
    }
}
