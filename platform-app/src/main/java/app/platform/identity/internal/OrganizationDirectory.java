package app.platform.identity.internal;

import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which organizations a person belongs to (ADR-0027). The one place in the identity module that works across
 * organizations: memberships are isolated per organization, so the question is asked in the narrow
 * {@link SystemScope#MEMBERSHIP_LOOKUP} scope, whose policy admits reading membership rows and nothing else. Every
 * query
 * here is by user; the scope is entered while the request's own organization is set aside, and only this class does it
 * (an architecture test).
 */
@Component
class OrganizationDirectory {

    /** An organization the person is an active member of. */
    record Organization(UUID tenantId, String slug, String displayName) {
    }

    private static final String SELECT = "select t.id, t.slug, t.display_name from membership m "
            + "join tenant t on t.id = m.tenant_id where m.user_id = :user and m.status = 'ACTIVE' "
            + "and m.deleted_at is null and t.status = 'ACTIVE' and t.deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;

    OrganizationDirectory(JdbcClient jdbc, TenantContexts contexts, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.transaction = transaction;
    }

    /** The organizations the person is an active member of, by name. */
    List<Organization> of(UUID userId) {
        return inScope(() -> jdbc.sql(SELECT + " order by t.display_name, t.slug limit 100")
                .param("user", userId)
                .query((rs, row) -> new Organization(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("display_name")))
                .list());
    }

    /** One of the person's organizations by short name; empty for an unknown one and for one they do not belong to. */
    Optional<Organization> find(UUID userId, String slug) {
        return inScope(() -> jdbc.sql(SELECT + " and t.slug = :slug")
                .param("user", userId)
                .param("slug", slug)
                .query((rs, row) -> new Organization(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("display_name")))
                .optional());
    }

    private <T> T inScope(java.util.function.Supplier<T> query) {
        java.util.function.Supplier<T> inTransaction = () -> transaction.execute(status -> query.get());
        // On an organization host the request has a tenant; the lookup sets it aside. On the platform host it has none.
        return contexts.current().isPresent()
                ? contexts.callAsSystemApart(SystemScope.MEMBERSHIP_LOOKUP, inTransaction)
                : contexts.callAsSystem(SystemScope.MEMBERSHIP_LOOKUP, inTransaction);
    }
}
