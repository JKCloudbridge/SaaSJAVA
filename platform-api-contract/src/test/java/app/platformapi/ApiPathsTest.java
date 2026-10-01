package app.platformapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiPathsTest {

    @Test
    void versionedPrefixStartsWithApiAndVersion() {
        assertThat(ApiPaths.V1).isEqualTo("/api/v1");
    }

    @Test
    void everyPathLivesUnderTheVersionedPrefix() {
        assertThat(ApiPaths.OPENAPI).startsWith(ApiPaths.V1 + "/");
        assertThat(ApiPaths.PLATFORM).startsWith(ApiPaths.V1 + "/");
        assertThat(ApiPaths.PLATFORM_STATUS).startsWith(ApiPaths.PLATFORM + "/");
        assertThat(ApiPaths.TENANT_CURRENT).startsWith(ApiPaths.TENANT + "/").startsWith(ApiPaths.V1 + "/");
    }

    @Test
    void headerNamesAreStable() {
        assertThat(ApiHeaders.REQUEST_ID).isEqualTo("X-Request-ID");
        assertThat(ApiHeaders.TRACE_ID).isEqualTo("X-Trace-Id");
    }
}
