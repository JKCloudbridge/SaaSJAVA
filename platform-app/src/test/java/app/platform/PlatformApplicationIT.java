package app.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * The application starts against a real PostgreSQL, migrates it, and serves its probes on the management port
 * only (the API port must not expose them).
 */
@PlatformIntegrationTest
class PlatformApplicationIT {

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void healthLivenessAndReadinessAreUpOnTheManagementPort() {
        RestClient client = RestClient.create("http://localhost:" + managementPort);

        assertThat(client.get().uri("/actuator/health").retrieve().body(String.class)).contains("\"status\":\"UP\"");
        assertThat(client.get().uri("/actuator/health/liveness").retrieve().body(String.class)).contains("UP");
        assertThat(client.get().uri("/actuator/health/readiness").retrieve().body(String.class)).contains("UP");
    }

    @Test
    void readinessDependsOnTheDatabase() {
        RestClient client = RestClient.create("http://localhost:" + managementPort);

        String readiness = client.get().uri("/actuator/health/readiness").retrieve().body(String.class);

        assertThat(readiness).contains("UP");
        // The readiness group includes the database check; its details stay hidden from callers.
        assertThat(readiness).doesNotContain("jdbc").doesNotContain("postgres");
    }

    @Test
    void probesAreNotServedOnTheApiPort() {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + apiPort)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        int status = client.get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(404);
    }
}
