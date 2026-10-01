package app.platform.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Looks at the identity tables and the audit table as the owner (the database administrator's view), the way a test
 * needs to: to prove what is stored, and to change stored values the application never changes (a lock, a time).
 */
public final class IdentityDb {

    private IdentityDb() {
    }

    /** One audit record as stored. */
    public record Audit(String type, String outcome, UUID userId, UUID tenantId, String reason, String attributes) {
    }

    /** All audit records about a user, oldest first. */
    public static List<Audit> auditOf(UUID userId) {
        return audit("actor_user_id = ?", userId);
    }

    /** Audit records of one type, oldest first (all users). */
    public static List<Audit> auditOfType(String type) {
        return audit("event_type = ?", type);
    }

    private static List<Audit> audit(String where, Object value) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement(
                        "select event_type, outcome, actor_user_id, context_tenant_id, reason, attributes::text "
                                + "from audit_record where " + where + " order by occurred_at, id")) {
            select.setObject(1, value);
            List<Audit> rows = new ArrayList<>();
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Audit(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class),
                            rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6)));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The whole audit table as text, for "this secret appears nowhere" checks. */
    public static String entireAuditTableAsText() {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement select = owner.prepareStatement("select t::text from audit_record t");
                ResultSet rs = select.executeQuery()) {
            StringBuilder all = new StringBuilder();
            while (rs.next()) {
                all.append(rs.getString(1)).append('\n');
            }
            return all.toString();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs a statement as the owner; returns the number of rows changed. */
    public static int execute(String sql, Object... parameters) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }

    /**
     * Runs a statement as the owner with the triggers switched off for that connection, to change what the triggers
     * protect (for example backdate a creation time to test an absolute lifetime). The test database's owner is a
     * superuser, which is what allows it.
     */
    public static int executeWithoutTriggers(String sql, Object... parameters) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection()) {
            try (java.sql.Statement setup = owner.createStatement()) {
                setup.execute("set session_replication_role = replica");
            }
            try (PreparedStatement statement = owner.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                return statement.executeUpdate();
            }
        }
    }

    /** Reads one value as the owner. */
    public static <T> T value(Class<T> type, String sql, Object... parameters) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getObject(1, type) : null;
            }
        }
    }

    /** Reads one text column of every row of a query, as the owner. */
    public static List<String> strings(String sql, Object... parameters) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            return values;
        }
    }
}
