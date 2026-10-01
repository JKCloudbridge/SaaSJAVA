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

/**
 * Checks that every table of a schema follows the base schema conventions (ADR-0010): the bookkeeping columns with
 * their types, a time-ordered identifier default, the row guard trigger, and unique indexes that ignore
 * soft-deleted rows. Any new table that breaks the pattern fails the integration build.
 */
final class SchemaConventions {

    /** Tables that are not platform data: the migration tool's own bookkeeping. */
    static final Set<String> EXEMPT_TABLES = Set.of("flyway_schema_history");

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
