package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;

/**
 * Where the token of a connected application may go (Sprint 31, ADR-0271), without a database: the public API and
 * nothing else. Without this rule the token would be a full session of the member it acts as.
 */
class ClientTokenAccessTest {

    @Test
    void thePublicApiIsTheOnlyPlaceAClientTokenMayGo() {
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/objects")).isTrue();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/objects/Employee__c/records/1")).isTrue();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/openapi")).isTrue();
    }

    @Test
    void everyAddressOfTheInternalInterfaceIsRefused() {
        List<String> internal = List.of("/api/v1/auth/me", "/api/v1/members", "/api/v1/profiles",
                "/api/v1/connected-apps", "/api/v1/connected-apps/catalogue", "/api/v1/credentials",
                "/api/v1/integrations", "/api/v1/objects/Employee__c/records", "/api/v1/metadata/objects",
                "/api/v1/embedded-tokens", "/api/v1/auth/sign-out-all", "/api/v1/platform/status",
                "/api/v1/tenant/current", "/api/v1/oauth2/token", "/api/v1/oauth2/revoke", "/api/v1/audit-events",
                "/api/v1/inbound/x/x", "/api/v1/openapi");

        internal.forEach(path -> assertThat(ClientTokenAccessFilter.permits(path)).as(path).isFalse());
    }

    @Test
    void anythingOutsideTheTwoApiPrefixesIsRefused() {
        assertThat(ClientTokenAccessFilter.permits("/")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/error")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v2/objects")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/public")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/actuator/health")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("")).isFalse();
        assertThat(ClientTokenAccessFilter.permits(null)).isFalse();
    }

    @Test
    void aPathThatIsOddlyShapedIsRefusedEvenUnderThePublicPrefix() {
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/../../v1/members")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/./objects")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1//objects")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/objects%2f..%2fmembers")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/objects;x=1")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/public/v1/objects\\..\\members")).isFalse();
    }

    @Test
    void aCaseVariantOfThePrefixIsNotThePublicApi() {
        assertThat(ClientTokenAccessFilter.permits("/API/public/v1/objects")).isFalse();
        assertThat(ClientTokenAccessFilter.permits("/api/Public/v1/objects")).isFalse();
    }

    @Test
    void aCallerIsAConnectedApplicationOnlyWhenItsTokenCarriesAClientIdentifier() {
        assertThat(ClientTokenAccessFilter.clientOf(token(Map.of("cid", "ca_abcdefghijklmnopqrstuvwx"))))
                .isEqualTo("ca_abcdefghijklmnopqrstuvwx");
        assertThat(ClientTokenAccessFilter.clientOf(token(Map.of()))).isNull();
        assertThat(ClientTokenAccessFilter.clientOf(token(Map.of("scope", List.of("openid"))))).isNull();
        assertThat(ClientTokenAccessFilter.clientOf(token(Map.of("cid", 7)))).isNull();
        assertThat(ClientTokenAccessFilter.clientOf(null)).isNull();
    }

    private static BearerTokenAuthentication token(Map<String, Object> attributes) {
        Map<String, Object> all = new java.util.HashMap<>(attributes);
        all.put("sub", "user-a");
        return new BearerTokenAuthentication(new DefaultOAuth2AuthenticatedPrincipal("user-a", all, List.of()),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                        Instant.now().plusSeconds(60)), List.of());
    }
}
