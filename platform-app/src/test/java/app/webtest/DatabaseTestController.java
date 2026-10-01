package app.webtest;

import app.platformapi.ApiResponse;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that look at the database connection from the inside (see {@link ConventionsTestController}). */
@RestController
@RequestMapping("/api/v1/test/db")
public class DatabaseTestController {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    DatabaseTestController(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The application name the database sees inside a transaction, and outside one on the same pool. */
    @GetMapping("/application-name")
    public ApiResponse<List<String>> applicationName() {
        String inside = transaction.execute(status -> currentApplicationName());
        String outside = currentApplicationName();
        return ApiResponse.of(List.of(inside, outside));
    }

    /** Runs a statement with a bind value inside a transaction and holds the connection for a moment. */
    @GetMapping("/bind")
    public ApiResponse<String> bind(@RequestParam String value, @RequestParam(defaultValue = "0") int holdMillis) {
        String echoed = transaction.execute(status -> {
            String result = jdbc.sql("select ?::text").param(value).query(String.class).single();
            jdbc.sql("select pg_sleep(?)").param(holdMillis / 1000.0).query().listOfRows();
            return result;
        });
        return ApiResponse.of(echoed);
    }

    private String currentApplicationName() {
        return jdbc.sql("select current_setting('application_name')").query(String.class).single();
    }
}
