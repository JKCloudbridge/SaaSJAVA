package app.platform.metadata.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads the metadata version of the current organization (ADR-0061): the counter that database triggers raise in the
 * same transaction as every change of an object or field definition (migration V031).
 *
 * <p>Two things are remembered for the length of one transaction, so that a request which asks the catalogue many times
 * (every permission decision does) pays for one read of the version and sees one consistent catalogue:
 * the version that was read, and whether this transaction has itself changed definitions. A transaction that has
 * changed definitions must see its own uncommitted changes and must not hand them to anybody else, so it bypasses the
 * cache; the code that changes definitions says so with {@link #wrote()}.
 */
@Component
class MetadataVersions {

    /** Stands for an organization that has never changed a definition since the counter exists. */
    static final long NEVER_CHANGED = -1;

    /**
     * What a reader needs to decide about the cache.
     *
     * @param version the committed version
     * @param transactionWrote whether the running transaction has changed definitions
     */
    record Reading(long version, boolean transactionWrote) {
    }

    /** What is remembered for one transaction. */
    private static final class Memo {
        private Reading reading;
        private boolean wrote;
    }

    private final JdbcClient jdbc;

    MetadataVersions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The version now. Must run inside the transaction that will also build the answer on a miss. */
    Reading read() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return query();
        }
        Memo memo = memo();
        if (memo.wrote) {
            return new Reading(NEVER_CHANGED, true);
        }
        if (memo.reading == null) {
            memo.reading = query();
        }
        return memo.reading;
    }

    /** Says that the running transaction changes definitions: from now on it reads the database itself. */
    void wrote() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            memo().wrote = true;
        }
    }

    private Reading query() {
        return jdbc.sql("select coalesce((select version from metadata_version where deleted_at is null), :never)")
                .param("never", NEVER_CHANGED)
                .query((rs, row) -> new Reading(rs.getLong(1), false))
                .single();
    }

    private Memo memo() {
        Object key = this;
        Memo memo = (Memo) TransactionSynchronizationManager.getResource(key);
        if (memo == null) {
            Memo created = new Memo();
            TransactionSynchronizationManager.bindResource(key, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(key);
                }
            });
            return created;
        }
        return memo;
    }
}
