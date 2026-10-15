package app.platform.sharedkernel.directory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The names of the records a module owns, for a screen that shows a link to one (Sprint 19, ADR-0129). It is the
 * different contract that ADR-0107 and {@link ReferenceDirectory} left for the first screen that needs a person's name
 * on a record (the owner of a record, a lookup to a user): a yes or no was deliberately not enough, and this is the
 * smallest thing that is. The owning module implements it as a bean; the record store collects every implementation,
 * and still never reads the module's tables.
 *
 * <p>What a name is allowed to reveal is decided by the owner, and the rule of the one implementation so far is the one
 * of {@link ReferenceDirectory}: only a record that a link could point to <em>now</em> is named (a person with an
 * active membership of the current organization). A missing identifier, one of another organization and one that is no
 * longer active all get no name, so nobody learns which of the three it was. Searching offers the same records and
 * nothing else, a bounded number at a time, so it cannot be used to list a directory page by page: the text must match.
 *
 * <p>Implementations answer for the organization of the thread's tenant context and run in the caller's transaction,
 * which must have been opened after the context. A name is text of a person, so callers never log it.
 */
public interface ReferenceLabels {

    /** One record a link could point to, as a picker offers it. */
    record Option(UUID id, String label) {

        /** Prints the identity only: the label is a person's text. */
        @Override
        public String toString() {
            return "Option[" + id + "]";
        }
    }

    /** The API name of the platform-owned object this answers for, for example {@code User}. */
    String object();

    /**
     * The names of the records among the identifiers that a link could point to now; the others are left out.
     *
     * @param identifiers the identifiers found in link fields, at most a few hundred
     */
    Map<UUID, String> labels(Collection<UUID> identifiers);

    /**
     * Records a link could point to whose name contains the text, best matches first.
     *
     * @param text what was typed; empty offers the first records in name order
     * @param limit the most to return, at least 1
     */
    List<Option> options(String text, int limit);
}
