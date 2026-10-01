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
    void theAuthenticationPathsLiveUnderTheirOwnPrefixesAndAreStable() {
        assertThat(ApiPaths.AUTH).isEqualTo("/api/v1/auth");
        assertThat(java.util.List.of(ApiPaths.AUTH_CSRF, ApiPaths.AUTH_SIGN_IN, ApiPaths.AUTH_START,
                ApiPaths.AUTH_CALLBACK, ApiPaths.AUTH_REFRESH, ApiPaths.AUTH_SIGN_OUT, ApiPaths.AUTH_SIGN_OUT_ALL,
                ApiPaths.AUTH_PASSWORD, ApiPaths.AUTH_ME)).allSatisfy(path -> assertThat(path).startsWith(
                        ApiPaths.AUTH + "/"));
        assertThat(java.util.List.of(ApiPaths.OAUTH2_AUTHORIZE, ApiPaths.OAUTH2_TOKEN, ApiPaths.OAUTH2_REVOKE,
                ApiPaths.OAUTH2_JWKS)).allSatisfy(path -> assertThat(path).startsWith(ApiPaths.OAUTH2 + "/"));
        assertThat(ApiPaths.AUTH_CALLBACK).isEqualTo("/api/v1/auth/callback");
    }

    @Test
    void headerNamesAreStable() {
        assertThat(ApiHeaders.REQUEST_ID).isEqualTo("X-Request-ID");
        assertThat(ApiHeaders.TRACE_ID).isEqualTo("X-Trace-Id");
    }
}
