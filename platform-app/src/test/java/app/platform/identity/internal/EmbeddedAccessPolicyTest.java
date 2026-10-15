package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/**
 * What the access token of a custom page's script may reach, and the one cross-origin rule it needs (Sprint 18,
 * ADR-0127). The list of rules is short on purpose; this test is where a change to it is made visible.
 */
class EmbeddedAccessPolicyTest {

    @Test
    void theDataInterfaceIsOpenForEveryMethod() {
        for (String method : List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) {
            assertThat(EmbeddedAccessPolicy.permits(method, "/api/v1/objects/Deal__c/records")).as(method).isTrue();
            assertThat(EmbeddedAccessPolicy.permits(method, "/api/v1/objects/Deal__c/records/0198/files")).isTrue();
            assertThat(EmbeddedAccessPolicy.permits(method, "/api/v1/objects/Deal__c/imports")).isTrue();
        }
    }

    @Test
    void definitionsAreReadableAndNeverChangeable() {
        for (String path : List.of("/api/v1/metadata/objects", "/api/v1/metadata/objects/Deal__c",
                "/api/v1/metadata/objects/Deal__c/layouts/Details__c", "/api/v1/metadata/field-types")) {
            assertThat(EmbeddedAccessPolicy.permits("GET", path)).as("GET " + path).isTrue();
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                assertThat(EmbeddedAccessPolicy.permits(method, path)).as(method + " " + path).isFalse();
            }
        }
    }

    @Test
    void whatAPageNeedsToDrawItselfIsReadable() {
        for (String path : List.of("/api/v1/tenant/current", "/api/v1/auth/me", "/api/v1/applications",
                "/api/v1/data-catalogue", "/api/v1/data-access/mine")) {
            assertThat(EmbeddedAccessPolicy.permits("GET", path)).as(path).isTrue();
            assertThat(EmbeddedAccessPolicy.permits("POST", path)).as("POST " + path).isFalse();
            assertThat(EmbeddedAccessPolicy.permits("DELETE", path)).as("DELETE " + path).isFalse();
        }
    }

    @Test
    void everythingThatSetsTheOrganizationUpOrDecidesWhoMayDoWhatIsOutOfReach() {
        for (String path : List.of("/api/v1/members", "/api/v1/members/0198/profile", "/api/v1/profiles",
                "/api/v1/access-policies", "/api/v1/roles", "/api/v1/groups", "/api/v1/sharing/rules",
                "/api/v1/abilities", "/api/v1/licences", "/api/v1/invitations", "/api/v1/audit-events",
                "/api/v1/metadata/change-sets", "/api/v1/metadata/releases", "/api/v1/metadata/applications",
                "/api/v1/metadata/tabs", "/api/v1/metadata/custom-pages", "/api/v1/metadata/custom-page-assets",
                "/api/v1/metadata/expressions/check", "/api/v1/applications/custom-pages/Inspection__c",
                "/api/v1/embedded-tokens", "/api/v1/organization/leave", "/api/v1/organization/sign-out-all",
                "/api/v1/auth/sign-in", "/api/v1/auth/sign-out", "/api/v1/auth/sign-out-all",
                "/api/v1/auth/refresh", "/api/v1/auth/password", "/api/v1/auth/switch", "/api/v1/oauth2/token",
                "/api/v1/platform/status", "/api/v1/platform/people", "/api/v1/support-access",
                "/api/v1/organizations", "/api/v1/openapi")) {
            for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                assertThat(EmbeddedAccessPolicy.permits(method, path)).as(method + " " + path).isFalse();
            }
        }
    }

    @Test
    void aPathThatIsNotWhatItLooksLikeIsRefusedOutright() {
        for (String path : List.of("/api/v1/objects/../members", "/api/v1/objects/./Deal__c/records",
                "/api/v1/objects//Deal__c", "/api/v1/objects/Deal__c%2f..%2f..%2fmembers", "/api/v1/objects/a;b",
                "/api/v1/objects/a\\b", "/api/v1/objects", "/api/v1/objectsx/records", "/api/v1/object/x",
                "/API/V1/OBJECTS/x", "/api/v1", "/api/v2/objects/x", "/objects/x", "", "api/v1/objects/x")) {
            assertThat(EmbeddedAccessPolicy.permits("GET", path)).as(path).isFalse();
        }
        assertThat(EmbeddedAccessPolicy.permits("GET", null)).isFalse();
    }

    @Test
    void aMethodThatIsNotListedIsRefused() {
        for (String method : List.of("TRACE", "OPTIONS", "CONNECT", "get", "PROPFIND", "")) {
            assertThat(EmbeddedAccessPolicy.permits(method, "/api/v1/objects/Deal__c/records")).as(method).isFalse();
        }
    }

    @Test
    void everyRuleSaysWhyItIsThere() {
        assertThat(EmbeddedAccessPolicy.reasons()).isNotEmpty().allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    // ---- the cross-origin rule ----

    @Test
    void theOriginOfASandboxedFrameGetsTheOneRuleAndNoCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "null");

        CorsConfiguration rule = new EmbeddedCors().getCorsConfiguration(request);

        assertThat(rule).isNotNull();
        assertThat(rule.getAllowedOrigins()).containsExactly("null");
        assertThat(rule.getAllowCredentials()).isFalse();
        assertThat(rule.getAllowedMethods()).containsExactlyInAnyOrder("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
        assertThat(rule.getAllowedHeaders()).contains("Authorization", "Content-Type").doesNotContain("*", "Cookie");
        assertThat(rule.getMaxAge()).isEqualTo(600L);
    }

    @Test
    void everyOtherRequestGetsNoRuleAtAll() {
        for (String origin : new String[] {null, "", "https://example.test", "http://localhost:3000", "NULL", "*"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (origin != null) {
                request.addHeader("Origin", origin);
            }
            assertThat(new EmbeddedCors().getCorsConfiguration(request)).as(String.valueOf(origin)).isNull();
        }
    }
}
