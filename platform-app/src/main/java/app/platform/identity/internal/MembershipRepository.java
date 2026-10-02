package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The membership table (ADR-0025). Tenant-scoped: every statement runs under the tenant context that was open when the
 * transaction began, and row level security refuses anything else. The tenant is never passed in; the column
 * default and the policy take it from the transaction.
 */
@Repository
class MembershipRepository {

    private final JdbcClient jdbc;

    MembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Makes the user a member of the current tenant and marks them as the organization's founding administrator. */
    UUID insertFounder(UUID userId, ActorId actor) {
        return jdbc.sql("insert into membership (user_id, founding_administrator, created_by, updated_by) "
                        + "values (:user, true, :actor, :actor) returning id")
                .param("user", userId)
                .param("actor", actor.value())
                .query(UUID.class)
                .single();
    }
}
