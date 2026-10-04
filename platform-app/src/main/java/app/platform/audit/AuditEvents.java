package app.platform.audit;

import app.platformapi.ApiPageResponse;
import app.platformapi.AuditEventView;
import app.platformapi.PageRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads audit events for the audit viewer (audit v1, ADR-0054, ADR-0056). Two readers, two views of one append-only
 * table, separated by the database itself (a row level security policy on who a row is for):
 * <ul>
 *   <li>{@link #ofOrganization}: the organization of the thread's tenant context, only rows that are for
 *       organizations;</li>
 *   <li>{@link #ofPlatform}: the rows that are for the platform, with no tenant context.</li>
 * </ul>
 * Neither reader takes an organization from its caller. Neither checks who is asking: the caller (a controller) has
 * already checked the ability or the platform role. Newest first, cursor paging.
 */
public interface AuditEvents {

    /**
     * What to look for. Every field is optional.
     *
     * @param from the earliest time, inclusive
     * @param to the latest time, exclusive
     * @param actor events about or by this person
     * @param kind a kind of event (for example {@code access.member.profile_set}) or a whole family (for example
     *        {@code access}, which also matches {@code access.group.created})
     * @param target an identifier an event was about (a record, a member, a group): matched against the record
     *        identifier and against the identifiers in the event's facts
     */
    record Query(Instant from, Instant to, UUID actor, String kind, String target) {

        private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
        private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

        /** Checks the shape of the text filters (they go into a database query as parameters, never as text). */
        public Query {
            if (kind != null && (kind.length() > 100 || !KIND.matcher(kind).matches())) {
                throw new IllegalArgumentException("kind");
            }
            if (target != null && !TARGET.matcher(target).matches()) {
                throw new IllegalArgumentException("target");
            }
            if (from != null && to != null && !from.isBefore(to)) {
                throw new IllegalArgumentException("from");
            }
        }

        /** No filter at all. */
        public static Query all() {
            return new Query(null, null, null, null, null);
        }
    }

    /** One page of the events of the organization the tenant context names. */
    ApiPageResponse<AuditEventView> ofOrganization(Query query, PageRequest page);

    /** One page of the events that are for the platform. Must run without a tenant context. */
    ApiPageResponse<AuditEventView> ofPlatform(Query query, PageRequest page);
}
