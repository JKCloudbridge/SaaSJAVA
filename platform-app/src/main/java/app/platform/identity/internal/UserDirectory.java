package app.platform.identity.internal;

import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.tenant.TenantContexts;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Answers the record store's question about the {@code User} object (Sprint 15A, ADR-0099): is this identifier a
 * person with an <strong>active</strong> membership of the current organization? A person who is not a member, a
 * member who has been deactivated and an identifier that names nobody all answer "no". The question is asked inside
 * the organization's tenant context and filters by it in addition to row level security.
 *
 * <p>It tells the record store nothing else: no name, no address, no status. A link that already exists to a member
 * who is deactivated later is not touched by this check (the record store only checks a link when it changes).
 */
@Component
class UserDirectory implements ReferenceDirectory {

    private static final String ACTIVE_MEMBER = "select exists (select 1 from membership where tenant_id = :tenant "
            + "and user_id = :user and status = 'ACTIVE' and deleted_at is null)";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    UserDirectory(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    @Override
    public String object() {
        return "User";
    }

    @Override
    public boolean exists(UUID identifier) {
        return jdbc.sql(ACTIVE_MEMBER).param("tenant", contexts.require().tenantId().value())
                .param("user", identifier).query(Boolean.class).single();
    }
}
