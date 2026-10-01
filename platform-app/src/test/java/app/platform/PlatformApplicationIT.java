package app.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestSignIn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private Users users;

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
        // Signed in, so that the answer is about the path, not about the missing sign-in (without one it is 401).
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + apiPort)
                .defaultHeader("Authorization", TestSignIn.bearerOnPlatformHost(apiPort, users))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        int status = client.get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(404);
    }
}
