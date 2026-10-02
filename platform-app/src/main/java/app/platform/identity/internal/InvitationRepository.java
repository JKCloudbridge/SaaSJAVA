package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The invitation table (ADR-0028). Tenant-scoped: every statement runs under the tenant context that was open when the
 * transaction began, and row level security refuses anything else, so an invitation of another organization is never
 * found, whatever identifier is asked for.
 */
@Repository
class InvitationRepository {

    static final String OPEN = "OPEN";
    static final String ACCEPTED = "ACCEPTED";
    static final String REVOKED = "REVOKED";

    /**
     * An invitation.
     *
     * @param status {@code OPEN}, {@code ACCEPTED} or {@code REVOKED}; an open invitation past {@code expiresAt} is
     *        shown as expired by the caller
     */
    record Invitation(UUID id, String email, boolean administrator, boolean founding, String status, Instant expiresAt,
            int sentCount, Instant createdAt) {

        boolean open(Instant now) {
            return OPEN.equals(status) && expiresAt.isAfter(now);
        }

        @Override
        public String toString() {
            // The address is personal data: it stays out of any log line that prints an invitation.
            return "Invitation[redacted]";
        }
    }

    private static final String COLUMNS = "id, email, administrator, founding_administrator, status, expires_at, "
            + "sent_count, created_at";

    private final JdbcClient jdbc;

    InvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an invitation of an address, or renews the open one: one open invitation per address and organization,
     * also when two administrators ask at the same moment (the unique index decides, the conflict clause renews).
     *
     * @return the invitation, with the expiry it now has
     */
    Invitation openOrRenew(String email, boolean administrator, Instant expiresAt, ActorId actor) {
        return jdbc.sql("insert into invitation (email, administrator, expires_at, created_by, updated_by) "
                        + "values (:email, :administrator, :expires, :actor, :actor) "
                        + "on conflict (tenant_id, email) where status = 'OPEN' and deleted_at is null do update "
                        + "set administrator = excluded.administrator, expires_at = excluded.expires_at, "
                        + "sent_count = invitation.sent_count + 1, version = invitation.version + 1, "
                        + "updated_by = excluded.updated_by "
                        + "returning " + COLUMNS)
                .param("email", email)
                .param("administrator", administrator)
                .param("expires", Timestamp.from(expiresAt))
                .param("actor", actor.value())
                .query(InvitationRepository::invitation)
                .single();
    }

    /** The invitations of the current organization, newest first. */
    List<Invitation> list() {
        return jdbc.sql("select " + COLUMNS + " from invitation where deleted_at is null "
                        + "order by created_at desc, id limit 200")
                .query(InvitationRepository::invitation)
                .list();
    }

    Optional<Invitation> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from invitation where id = :id and deleted_at is null")
                .param("id", id)
                .query(InvitationRepository::invitation)
                .optional();
    }

    /** The invitation, locked until the transaction ends (two acceptances of one invitation have one winner). */
    Optional<Invitation> findForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from invitation where id = :id and deleted_at is null for update")
                .param("id", id)
                .query(InvitationRepository::invitation)
                .optional();
    }

    /** Extends an open invitation and counts the new mail. @return whether it was open */
    boolean renew(UUID id, Instant expiresAt, ActorId actor) {
        return jdbc.sql("update invitation set expires_at = :expires, sent_count = sent_count + 1, "
                        + "version = version + 1, updated_by = :actor "
                        + "where id = :id and status = 'OPEN' and deleted_at is null")
                .param("expires", Timestamp.from(expiresAt))
                .param("actor", actor.value())
                .param("id", id)
                .update() == 1;
    }

    /** Closes an open invitation as revoked. @return whether it was open */
    boolean revoke(UUID id, ActorId actor) {
        return jdbc.sql("update invitation set status = 'REVOKED', resolved_at = now(), resolved_by = :actor, "
                        + "version = version + 1, updated_by = :actor "
                        + "where id = :id and status = 'OPEN' and deleted_at is null")
                .param("actor", actor.value())
                .param("id", id)
                .update() == 1;
    }

    /** Closes an open invitation as accepted by the user, who now holds the membership. */
    boolean accept(UUID id, UUID membershipId, ActorId actor) {
        return jdbc.sql("update invitation set status = 'ACCEPTED', resolved_at = now(), resolved_by = :actor, "
                        + "membership_id = :membership, version = version + 1, updated_by = :actor "
                        + "where id = :id and status = 'OPEN' and deleted_at is null")
                .param("actor", actor.value())
                .param("membership", membershipId)
                .param("id", id)
                .update() == 1;
    }

    private static Invitation invitation(ResultSet rs, int row) throws SQLException {
        return new Invitation(rs.getObject("id", UUID.class), rs.getString("email"), rs.getBoolean("administrator"),
                rs.getBoolean("founding_administrator"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("sent_count"),
                rs.getTimestamp("created_at").toInstant());
    }
}
