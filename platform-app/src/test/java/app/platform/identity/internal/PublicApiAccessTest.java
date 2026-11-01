package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The closed table of public operations and the seam for scopes (Sprint 30, ADR-0244, ADR-0249), without a database.
 * Scope is necessary, never sufficient: a token without scopes reaches the table, a token with scopes only what its
 * scopes name, and nothing here ever says yes to something outside the table.
 */
class PublicApiAccessTest {

    private static final String OBJECTS = "/api/public/v1/objects";

    @Test
    void everyOperationOfTheTableIsFoundByItsMethodAndPath() {
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS)).map(PublicApiAccess.Operation::name)
                .contains("listObjects");
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS + "/Employee__c")).map(PublicApiAccess.Operation::name)
                .contains("describeObject");
        assertThat(PublicApiAccess.operationOf("POST", OBJECTS + "/Employee__c/records"))
                .map(PublicApiAccess.Operation::name).contains("createRecord");
        assertThat(PublicApiAccess.operationOf("POST", OBJECTS + "/Employee__c/records/query"))
                .map(PublicApiAccess.Operation::name).contains("queryRecords");
        assertThat(PublicApiAccess.operationOf("POST", OBJECTS + "/Employee__c/records/bulk"))
                .map(PublicApiAccess.Operation::name).contains("bulkRecords");
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS + "/Employee__c/records/0194f2a8"))
                .map(PublicApiAccess.Operation::name).contains("getRecord");
        assertThat(PublicApiAccess.operationOf("PATCH", OBJECTS + "/Employee__c/records/0194f2a8"))
                .map(PublicApiAccess.Operation::name).contains("updateRecord");
        assertThat(PublicApiAccess.operationOf("DELETE", OBJECTS + "/Employee__c/records/0194f2a8"))
                .map(PublicApiAccess.Operation::name).contains("deleteRecord");
        assertThat(PublicApiAccess.operationOf("POST", OBJECTS + "/Employee__c/records/0194f2a8/restore"))
                .map(PublicApiAccess.Operation::name).contains("restoreRecord");
        assertThat(PublicApiAccess.operationOf("GET", "/api/public/v1/openapi")).map(PublicApiAccess.Operation::name)
                .contains("getPublicApiDescription");
    }

    @Test
    void headIsTheSameOperationAsGet() {
        assertThat(PublicApiAccess.operationOf("HEAD", OBJECTS)).isPresent();
    }

    @Test
    void whatIsNotInTheTableIsNotAnOperation() {
        // Nothing of the setup of the platform, of files, imports, sharing or the console is public.
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS + "/Employee__c/records")).isEmpty();
        assertThat(PublicApiAccess.operationOf("PUT", OBJECTS + "/Employee__c/records/1")).isEmpty();
        assertThat(PublicApiAccess.operationOf("POST", OBJECTS + "/Employee__c/records/1/files")).isEmpty();
        assertThat(PublicApiAccess.operationOf("POST", OBJECTS + "/Employee__c/imports")).isEmpty();
        assertThat(PublicApiAccess.operationOf("GET", "/api/public/v1/metadata/objects")).isEmpty();
        assertThat(PublicApiAccess.operationOf("GET", "/api/v1/objects/Employee__c/records")).isEmpty();
        assertThat(PublicApiAccess.operationOf("DELETE", OBJECTS)).isEmpty();
    }

    @Test
    void anOddlyShapedPathIsNeverAnOperation() {
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS + "/../objects")).isEmpty();
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS + "//Employee__c")).isEmpty();
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS + "/Employee%5F_c")).isEmpty();
        assertThat(PublicApiAccess.operationOf("GET", OBJECTS + "/a;b")).isEmpty();
        assertThat(PublicApiAccess.operationOf("GET", null)).isEmpty();
    }

    @Test
    void aTokenWithoutScopesReachesEveryOperationOfTheTable() {
        PublicApiAccess.OPERATIONS.forEach(operation ->
                assertThat(PublicApiAccess.permits(operation, Optional.empty())).as(operation.name()).isTrue());
    }

    @Test
    void aTokenWithScopesReachesOnlyTheOperationsItsScopesName() {
        PublicApiAccess.Operation read = PublicApiAccess.operationOf("GET", OBJECTS + "/E__c/records/1").orElseThrow();
        PublicApiAccess.Operation write = PublicApiAccess.operationOf("POST", OBJECTS + "/E__c/records").orElseThrow();

        assertThat(PublicApiAccess.permits(read, Optional.of(Set.of("records.read")))).isTrue();
        assertThat(PublicApiAccess.permits(write, Optional.of(Set.of("records.read")))).isFalse();
        assertThat(PublicApiAccess.permits(write, Optional.of(Set.of("records.read", "records.write")))).isTrue();
        assertThat(PublicApiAccess.permits(read, Optional.of(Set.of()))).isFalse();
    }

    @Test
    void theDescriptionNeedsNoScope() {
        PublicApiAccess.Operation description = PublicApiAccess.operationOf("GET", "/api/public/v1/openapi")
                .orElseThrow();

        assertThat(PublicApiAccess.permits(description, Optional.of(Set.of()))).isTrue();
    }

    @Test
    void everyOperationNamesTheScopeItWillNeed() {
        PublicApiAccess.OPERATIONS.stream().filter(operation -> !"getPublicApiDescription".equals(operation.name()))
                .forEach(operation -> assertThat(operation.scope()).as(operation.name()).isNotBlank());
        assertThat(PublicApiAccess.names()).doesNotHaveDuplicates();
    }

    @Test
    void theScopesOfATokenAreReadFromItsScopeAttributeAsAListOrAsText() {
        assertThat(PublicApiAccessFilter.scopesOf(token(Map.of()))).isEmpty();
        assertThat(PublicApiAccessFilter.scopesOf(token(Map.of("api_scopes", List.of("records.read", "objects.read")))))
                .contains(Set.of("records.read", "objects.read"));
        assertThat(PublicApiAccessFilter.scopesOf(token(Map.of("api_scopes", "records.read  records.write"))))
                .contains(Set.of("records.read", "records.write"));
        assertThat(PublicApiAccessFilter.scopesOf(null)).isEmpty();
        // The sign-in scopes of the web application's own tokens say nothing about the public API.
        assertThat(PublicApiAccessFilter.scopesOf(token(Map.of("scope", List.of("openid"))))).isEmpty();
    }

    private static BearerTokenAuthentication token(Map<String, Object> attributes) {
        Map<String, Object> all = new java.util.HashMap<>(attributes);
        all.put("sub", "user-a");
        return new BearerTokenAuthentication(new DefaultOAuth2AuthenticatedPrincipal("user-a", all, List.of()),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                        Instant.now().plusSeconds(60)), List.of());
    }
}
