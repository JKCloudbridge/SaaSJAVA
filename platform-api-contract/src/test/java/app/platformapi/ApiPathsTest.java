package app.platformapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiPathsTest {

    @Test
    void versionedPrefixStartsWithApiAndVersion() {
        assertThat(ApiPaths.V1).isEqualTo("/api/v1");
    }
}
