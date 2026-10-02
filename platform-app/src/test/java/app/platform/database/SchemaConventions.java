package app.platform.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that every table of a schema follows the base schema conventions (ADR-0010): the bookkeeping columns with
 * their types, a time-ordered identifier default, the row guard trigger, and unique indexes that ignore
 * soft-deleted rows; and the tenant rules (ADR-0015): a table either belongs to a tenant (a {@code tenant_id} column
 * and row level security that is enabled, forced and bound to the tenant of the transaction, with the tenant guard
 * trigger and a tenant-first index) or is declared a platform-level table in {@link #PLATFORM_TABLES}. Any new table
 * that breaks the pattern fails the integration build.
 */
final class SchemaConventions {

    /** Tables that are not platform data: the migration tool's own bookkeeping. */
    static final Set<String> EXEMPT_TABLES = Set.of("flyway_schema_history");

    /**
     * Platform-level tables: they have no {@code tenant_id} and no row level security because they are the thing
     * tenants are defined by, or platform infrastructure. Adding a name here is a security decision (ADR-0015): every
     * other table must belong to a tenant.
     *
     * <p>Sprint 3 added the identity tables and audit v0 (ADR-0022): a user is a global identity (one person, several
     * organizations), so users, credentials, login sessions and authorization records cannot belong to one tenant,
     * and an authentication event can happen where there is no tenant at all. Their tenant-related column is named
     * {@code bound_tenant_id} or {@code context_tenant_id} so that nobody mistakes them for tenant-scoped data.
     *
     * <p>Sprint 4 added {@code account_token} (one-time sign-up and reset link tokens, ADR-0023) and
     * {@code mail_queue} (the e-mail queue, ADR-0024): both are used on the platform host where there is no tenant,
     * and neither carries a tenant column at all.
     *
     * <p>Sprint 5 added {@code organization_handoff} (a signed-in person's one-time request to continue on another
     * organization's host, ADR-0029): it exists so that a session can be started on a different host, and it names the
     * target organization in {@code bound_tenant_id}. It also let {@code membership} admit the read-only
     * {@code membership_lookup} system scope (ADR-0027).
     */
    static final Set<String> PLATFORM_TABLES = Set.of("tenant", "platform_user", "user_credential", "login_session",
            "oauth2_authorization", "audit_record", "account_token", "mail_queue", "organization_handoff");

    /** Tables whose policies may admit a system scope next to the tenant, and the scopes they may name. */
    static final Set<String> SYSTEM_SCOPE_TABLES = Set.of("outbox_event", "processed_event", "membership");

    /** The system scopes that exist (the values of the {@code SystemScope} enum of the tenant module). */
    static final Set<String> SYSTEM_SCOPES = Set.of("outbox_relay", "membership_lookup");

    private static final String CURRENT_TENANT = "platform_current_tenant";
    private static final Pattern SCOPE_NAME = Pattern.compile("platform_in_system_scope\\('([a-z_]+)'");
    private static final Pattern TRUE_LITERAL = Pattern.compile("\\btrue\\b", Pattern.CASE_INSENSITIVE);

    /** Column name to expected data type and nullability ("not null" or "nullable"). */
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        REQUIRED_COLUMNS.put("id", "uuid not null");
        REQUIRED_COLUMNS.put("version", "bigint not null");
        REQUIRED_COLUMNS.put("created_at", "timestamp with time zone not null");
        REQUIRED_COLUMNS.put("created_by", "uuid not null");
        REQUIRED_COLUMNS.put("updated_at", "timestamp with time zone not null");
        REQUIRED_COLUMNS.put("updated_by", "uuid not null");
        REQUIRED_COLUMNS.put("deleted_at", "timestamp with time zone nullable");
        REQUIRED_COLUMNS.put("deleted_by", "uuid nullable");
    }

    private SchemaConventions() {
    }

    /**
     * Lists the convention violations of every table in the schema.
     *
     * @return one sentence per violation, each starting with the table name; empty when all tables conform
     */
    static List<String> problems(Connection connection, String schema) throws SQLException {
        return problems(connection, schema, PLATFORM_TABLES);
    }

    /**
     * Like {@link #problems(Connection, String)} with an explicit list of platform-level tables, for schemas built by
     * a test.
     */
    static List<String> problems(Connection connection, String schema, Set<String> platformTables)
            throws SQLException {
        List<String> problems = new ArrayList<>();
        for (String table : tables(connection, schema)) {
            if (EXEMPT_TABLES.contains(table)) {
                continue;
            }
            columnProblems(connection, schema, table, problems);
            primaryKeyProblems(connection, schema, table, problems);
            defaultProblems(connection, schema, table, problems);
            triggerProblems(connection, schema, table, problems);
            uniqueIndexProblems(connection, schema, table, problems);
            tenantProblems(connection, schema, table, platformTables, problems);
        }
        return problems;
    }

    private static List<String> tables(Connection connection, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select table_name from information_schema.tables where table_schema = ? "
                        + "and table_type = 'BASE TABLE' order by table_name")) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private static void columnProblems(Connection connection, String schema, String table, List<String> problems)
            throws SQLException {
        Map<String, String> actual = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select column_name, data_type, is_nullable from information_schema.columns "
                        + "where table_schema = ? and table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    actual.put(rs.getString(1), rs.getString(2) + ("YES".equals(rs.getString(3))
                            ? " nullable" : " not null"));
                }
            }
        }
        REQUIRED_COLUMNS.forEach((column, expected) -> {
            String found = actual.get(column);
            if (found == null) {
                problems.add(table + ": missing column " + column + " (" + expected + ")");
            } else if (!found.equals(expected)) {
                problems.add(table + ": column " + column + " is '" + found + "', expected '" + expected + "'");
            }
        });
    }

    private static void primaryKeyProblems(Connection connection, String schema, String table, List<String> problems)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid "
                        + "and a.attnum = any(i.indkey) where i.indrelid = ?::regclass and i.indisprimary")) {
            statement.setString(1, schema + "." + table);
            List<String> keyColumns = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    keyColumns.add(rs.getString(1));
                }
            }
            if (!keyColumns.equals(List.of("id"))) {
                problems.add(table + ": the primary key must be exactly (id), found " + keyColumns);
            }
        }
    }

    private static void defaultProblems(Connection connection, String schema, String table, List<String> problems)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select column_name, column_default from information_schema.columns "
                        + "where table_schema = ? and table_name = ? and column_name in ('id', 'version')")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String column = rs.getString(1);
                    String expression = rs.getString(2);
                    if ("id".equals(column) && (expression == null || !expression.contains("uuidv7()"))) {
                        problems.add(table + ": id must default to uuidv7(), found " + expression);
                    }
                    if ("version".equals(column) && !"0".equals(expression)) {
                        problems.add(table + ": version must default to 0, found " + expression);
                    }
                }
            }
        }
    }

    private static void triggerProblems(Connection connection, String schema, String table, List<String> problems)
            throws SQLException {
        // tgtype bits: 1 = row level, 2 = before, 4 = insert, 8 = delete, 16 = update.
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from pg_trigger t join pg_proc p on p.oid = t.tgfoid "
                        + "where t.tgrelid = ?::regclass and not t.tgisinternal "
                        + "and p.proname = 'platform_row_guard' and (t.tgtype & 31) = 23")) {
            statement.setString(1, schema + "." + table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 1) {
                    problems.add(table + ": needs exactly one row-level BEFORE INSERT OR UPDATE trigger calling "
                            + "platform_row_guard()");
                }
            }
        }
    }

    // ---- tenant rules (ADR-0015) ----

    private static void tenantProblems(Connection connection, String schema, String table,
            Set<String> platformTables, List<String> problems) throws SQLException {
        boolean scoped = hasTenantColumn(connection, schema, table);
        if (!scoped) {
            if (!platformTables.contains(table)) {
                problems.add(table + ": has no tenant_id column and is not a declared platform-level table; a table "
                        + "belongs to a tenant (tenant_id, row level security) unless it is listed as platform-level");
            }
            return;
        }
        if (platformTables.contains(table)) {
            problems.add(table + ": is declared platform-level but has a tenant_id column");
        }
        tenantColumnProblems(connection, schema, table, problems);
        rowSecurityProblems(connection, schema, table, problems);
        policyProblems(connection, schema, table, problems);
        tenantGuardProblems(connection, schema, table, problems);
        tenantIndexProblems(connection, schema, table, problems);
    }

    private static boolean hasTenantColumn(Connection connection, String schema, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from information_schema.columns where table_schema = ? and table_name = ? "
                        + "and column_name = 'tenant_id'")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    private static void tenantColumnProblems(Connection connection, String schema, String table,
            List<String> problems) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select data_type, is_nullable from information_schema.columns where table_schema = ? "
                        + "and table_name = ? and column_name = 'tenant_id'")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                if (!"uuid".equals(rs.getString(1)) || !"NO".equals(rs.getString(2))) {
                    problems.add(table + ": tenant_id must be 'uuid not null', found '" + rs.getString(1)
                            + ("YES".equals(rs.getString(2)) ? " nullable" : " not null") + "'");
                }
            }
        }
    }

    private static void rowSecurityProblems(Connection connection, String schema, String table,
            List<String> problems) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select relrowsecurity, relforcerowsecurity from pg_class where oid = ?::regclass")) {
            statement.setString(1, schema + "." + table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    problems.add(table + ": row level security is not enabled");
                }
                if (!rs.getBoolean(2)) {
                    problems.add(table + ": row level security is not FORCED, so the table owner would bypass it");
                }
            }
        }
    }

    private static void policyProblems(Connection connection, String schema, String table, List<String> problems)
            throws SQLException {
        int policies = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "select polname, polcmd, pg_get_expr(polqual, polrelid), pg_get_expr(polwithcheck, polrelid) "
                        + "from pg_policy where polrelid = ?::regclass")) {
            statement.setString(1, schema + "." + table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    policies++;
                    String name = rs.getString(1);
                    String command = rs.getString(2);
                    String using = rs.getString(3);
                    String check = rs.getString(4);
                    // An insert policy has only a check; select and delete only a using; update and all may have both
                    // (a missing check on update and all defaults to the using expression).
                    boolean needsUsing = !"a".equals(command);
                    boolean needsCheck = "a".equals(command);
                    if (needsUsing) {
                        expressionProblems(table, name, "using", using, true, problems);
                    }
                    if (needsCheck) {
                        expressionProblems(table, name, "with check", check, true, problems);
                    } else if (check != null) {
                        expressionProblems(table, name, "with check", check, false, problems);
                    }
                    scopeProblems(table, name, using, check, problems);
                }
            }
        }
        if (policies == 0) {
            problems.add(table + ": row level security without any policy denies everything to the application; "
                    + "it needs a tenant policy");
        }
    }

    private static void expressionProblems(String table, String policy, String part, String expression,
            boolean required, List<String> problems) {
        if (expression == null) {
            if (required) {
                problems.add(table + ": policy " + policy + " has no " + part + " expression");
            }
            return;
        }
        if (!expression.contains(CURRENT_TENANT) || !expression.contains("tenant_id")) {
            problems.add(table + ": policy " + policy + " (" + part + ") does not compare tenant_id with "
                    + CURRENT_TENANT + "(), found " + expression);
        }
        if (TRUE_LITERAL.matcher(expression).find()) {
            problems.add(table + ": policy " + policy + " (" + part + ") contains a literal true, found " + expression);
        }
    }

    private static void scopeProblems(String table, String policy, String using, String check,
            List<String> problems) {
        for (String expression : new String[] {using, check}) {
            if (expression == null || !expression.contains("platform_in_system_scope")) {
                continue;
            }
            if (!SYSTEM_SCOPE_TABLES.contains(table)) {
                problems.add(table + ": policy " + policy + " admits a system scope, which only "
                        + SYSTEM_SCOPE_TABLES + " may do");
            }
            Matcher matcher = SCOPE_NAME.matcher(expression);
            while (matcher.find()) {
                if (!SYSTEM_SCOPES.contains(matcher.group(1))) {
                    problems.add(table + ": policy " + policy + " names an unknown system scope");
                }
            }
        }
    }

    private static void tenantGuardProblems(Connection connection, String schema, String table,
            List<String> problems) throws SQLException {
        // tgtype bits: 1 = row level, 2 = before, 16 = update.
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from pg_trigger t join pg_proc p on p.oid = t.tgfoid "
                        + "where t.tgrelid = ?::regclass and not t.tgisinternal "
                        + "and p.proname = 'platform_tenant_guard' and (t.tgtype & 19) = 19")) {
            statement.setString(1, schema + "." + table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 1) {
                    problems.add(table + ": needs exactly one row-level BEFORE UPDATE trigger calling "
                            + "platform_tenant_guard() so a row can never change tenant");
                }
            }
        }
    }

    private static void tenantIndexProblems(Connection connection, String schema, String table,
            List<String> problems) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from pg_index i where i.indrelid = ?::regclass and i.indkey[0] = "
                        + "(select a.attnum from pg_attribute a where a.attrelid = i.indrelid "
                        + "and a.attname = 'tenant_id')")) {
            statement.setString(1, schema + "." + table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                if (rs.getInt(1) < 1) {
                    problems.add(table + ": needs an index that starts with tenant_id, or every isolated query "
                            + "scans the whole table");
                }
            }
        }
    }

    private static void uniqueIndexProblems(Connection connection, String schema, String table, List<String> problems)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select c.relname, pg_get_expr(i.indpred, i.indrelid) from pg_index i "
                        + "join pg_class c on c.oid = i.indexrelid "
                        + "where i.indrelid = ?::regclass and i.indisunique and not i.indisprimary")) {
            statement.setString(1, schema + "." + table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String predicate = rs.getString(2);
                    if (predicate == null || !predicate.toLowerCase().contains("deleted_at is null")) {
                        problems.add(table + ": unique index " + rs.getString(1)
                                + " must be partial (where deleted_at is null) so a soft-deleted row does not block "
                                + "its key");
                    }
                }
            }
        }
    }
}
