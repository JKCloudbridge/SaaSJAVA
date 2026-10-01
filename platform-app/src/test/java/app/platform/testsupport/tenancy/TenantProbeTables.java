package app.platform.testsupport.tenancy;

import app.platform.sharedkernel.ActorId;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates throw-away tenant-scoped tables for tests, in {@code public} so that the application role gets its
 * privileges the way it does for every real table (default privileges of manual migration M001). {@link #standard}
 * is the reference implementation of the pattern every tenant-scoped table follows (ADR-0015); the broken variants
 * exist to prove that the leak checks notice each kind of mistake.
 */
public final class TenantProbeTables {

    /** One way to get the pattern wrong. */
    public enum Defect {
        /** Row level security is not switched on. */
        NO_RLS,
        /** Switched on but not forced, so the table owner bypasses it (found by the catalog check). */
        NOT_FORCED,
        /** A policy that lets everyone see everything. */
        OPEN_POLICY,
        /** The policy treats a missing tenant as "all tenants" (fails open). */
        FAILS_OPEN,
        /** Reading is isolated, but inserts are checked against nothing. */
        OPEN_INSERT,
        /** The policy compares with the wrong column. */
        WRONG_COLUMN
    }

    private TenantProbeTables() {
    }

    /** Creates a table built exactly to the pattern and returns its registration. */
    public static TenantScopedTable standard(Connection owner, String name) throws SQLException {
        return create(owner, name, null);
    }

    /** Creates a table with one defect and returns its registration. */
    public static TenantScopedTable broken(Connection owner, String name, Defect defect) throws SQLException {
        return create(owner, name, defect);
    }

    /** Drops a probe table. */
    public static void drop(Connection owner, String name) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            statement.execute("drop table if exists " + name);
        }
    }

    private static TenantScopedTable create(Connection owner, String name, Defect defect) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            statement.execute("drop table if exists " + name);
            statement.execute("create table " + name + " ("
                    + "id uuid primary key default uuidv7(), "
                    + "tenant_id uuid not null default platform_current_tenant() references tenant (id), "
                    + "note text, "
                    + "version bigint not null default 0, created_at timestamptz not null default now(), "
                    + "created_by uuid not null, updated_at timestamptz not null default now(), "
                    + "updated_by uuid not null, deleted_at timestamptz, deleted_by uuid)");
            statement.execute("create trigger " + name + "_row_guard before insert or update on " + name
                    + " for each row execute function platform_row_guard()");
            statement.execute("create trigger " + name + "_tenant_guard before update on " + name
                    + " for each row execute function platform_tenant_guard()");
            statement.execute("create index " + name + "_tenant on " + name + " (tenant_id, created_at)");
            if (defect != Defect.NO_RLS) {
                statement.execute("alter table " + name + " enable row level security");
                if (defect != Defect.NOT_FORCED) {
                    statement.execute("alter table " + name + " force row level security");
                }
                for (String policy : policies(name, defect)) {
                    statement.execute(policy);
                }
            }
        }
        return new TenantScopedTable(name, (connection, tenant) -> TenantFixtures.update(connection,
                "insert into " + name + " (tenant_id, note, created_by, updated_by) values (?, 'probe', ?, ?)",
                tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));
    }

    private static String[] policies(String name, Defect defect) {
        String isolated = "tenant_id = (select platform_current_tenant())";
        if (defect == null || defect == Defect.NOT_FORCED) {
            return new String[] {"create policy " + name + "_isolation on " + name + " for all using (" + isolated
                    + ") with check (" + isolated + ")"};
        }
        return switch (defect) {
            case OPEN_POLICY -> new String[] {"create policy " + name + "_open on " + name + " for all using (true) "
                    + "with check (true)"};
            case FAILS_OPEN -> new String[] {"create policy " + name + "_open on " + name + " for all using ("
                    + "current_setting('app.current_tenant', true) is null "
                    + "or current_setting('app.current_tenant', true) = '' or " + isolated + ") with check ("
                    + isolated + ")"};
            case OPEN_INSERT -> new String[] {
                "create policy " + name + "_read on " + name + " for select using (" + isolated + ")",
                "create policy " + name + "_update on " + name + " for update using (" + isolated + ") with check ("
                        + isolated + ")",
                "create policy " + name + "_delete on " + name + " for delete using (" + isolated + ")",
                "create policy " + name + "_insert on " + name + " for insert with check (true)"};
            case WRONG_COLUMN -> new String[] {"create policy " + name + "_wrong on " + name + " for all using ("
                    + "id = (select platform_current_tenant())) with check (id = (select platform_current_tenant()))"};
            default -> new String[0];
        };
    }
}
