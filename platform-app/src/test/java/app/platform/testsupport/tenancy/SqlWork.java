package app.platform.testsupport.tenancy;

import java.sql.Connection;
import java.sql.SQLException;

/** Database work done on one connection inside one transaction. */
@FunctionalInterface
public interface SqlWork<T> {

    /** Runs the work. */
    T run(Connection connection) throws SQLException;
}
