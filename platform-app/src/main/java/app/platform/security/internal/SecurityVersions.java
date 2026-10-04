package app.platform.security.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the security version of the current organization (ADR-0053): the counter that database triggers raise in the
 * same transaction as every change that decides what a member may do (migration V028).
 *
 * <p>The read is one lookup by the organization's key. It also says whether the running transaction has already
 * written something: such a transaction must see its own uncommitted changes and must not hand its view to anybody
 * else, so the cache is bypassed for it.
 */
@Component
class SecurityVersions {

    /** Stands for an organization that has never changed anything since the counter exists. */
    static final long NEVER_CHANGED = -1;

    private final JdbcClient jdbc;

    SecurityVersions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What a reader needs to decide about the cache.
     *
     * @param version the committed version, or the transaction's own raised one when it wrote
     * @param transactionWrote whether the running transaction has written anything yet
     */
    record Reading(long version, boolean transactionWrote) {
    }

    /** The version now. Must run inside the transaction that will also compute the answer on a miss. */
    Reading read() {
        return jdbc.sql("select coalesce((select version from security_version where deleted_at is null), :never) "
                        + "as version, pg_current_xact_id_if_assigned() is not null as wrote")
                .param("never", NEVER_CHANGED)
                .query((rs, row) -> new Reading(rs.getLong("version"), rs.getBoolean("wrote")))
                .single();
    }
}
