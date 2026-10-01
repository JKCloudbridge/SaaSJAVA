package app.platform.testsupport.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A tenant-scoped table registered for the cross-tenant leak checks. To put a new table under test, add one entry to
 * {@link TenantScopedTables#ALL}: its name and a way to insert one valid row.
 *
 * @param name the table name, schema-qualified when it is not in {@code public}
 * @param inserter inserts one valid row whose {@code tenant_id} is the given tenant; it must name the tenant column
 *        explicitly (never rely on the column default), because the leak checks also use it to forge rows for another
 *        tenant
 */
public record TenantScopedTable(String name, RowInserter inserter) {

    /** Inserts one valid row. */
    @FunctionalInterface
    public interface RowInserter {

        /**
         * @param connection a connection of the application role inside a transaction
         * @param rowTenant the value of the row's {@code tenant_id}
         */
        void insert(Connection connection, UUID rowTenant) throws SQLException;
    }
}
