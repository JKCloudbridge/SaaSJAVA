package app.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the integration-test wiring: a real PostgreSQL runs in a container, never an in-memory
 * substitute. Later sprints build their database tests on the same mechanism.
 */
@Testcontainers
class PostgresContainerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Test
    void realPostgresqlAcceptsConnectionsAndSupportsRowLevelSecurity() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select current_setting('server_version_num')::int")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(180000);
            }
            statement.execute("create table probe (id int primary key, tenant text not null)");
            statement.execute("alter table probe enable row level security");
            try (ResultSet rs = statement.executeQuery(
                    "select relrowsecurity from pg_class where relname = 'probe'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }
}
