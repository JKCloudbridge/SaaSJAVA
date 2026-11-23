package app.platform.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the published contract of the public API may and may not contain (Sprint 30, ADR-0248), read from the committed
 * documents, without a database: nothing of the internal interface (no internal path, no internal schema), no host, no
 * time, no word of the project's own working vocabulary, and no operation outside the closed table.
 */
class PublicContractContentTest {

    private static final Path PUBLIC =
            Path.of("../platform-api-contract/src/main/resources/openapi/platform-public-api-v1.json");
    private static final Path INTERNAL =
            Path.of("../platform-api-contract/src/main/resources/openapi/platform-api-v1.json");

    /** The error model and the page, which both interfaces share by design (ADR-0011). */
    private static final Set<String> SHARED = Set.of("ApiError", "ApiErrorResponse", "ErrorCode", "Pagination");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyPathIsUnderThePublicPrefix() throws IOException {
        JsonNode document = JSON.readTree(Files.readString(PUBLIC));

        document.path("paths").properties().forEach(path ->
                assertThat(path.getKey()).startsWith("/api/public/v1/"));
    }

    @Test
    void noInternalPathAppearsExceptTheOneTokenAddressOfTheClientCredentialsFlow() throws IOException {
        // Sprint 31 (ADR-0270): software must be told where to ask for a token. It is the only internal address a
        // customer needs, it is written relative to the host the document is served from, and it appears once.
        String document = Files.readString(PUBLIC);

        assertThat(document.split("/api/v1/", -1).length - 1).as("occurrences of an internal prefix").isEqualTo(1);
        assertThat(document).contains("\"tokenUrl\" : \"/api/v1/oauth2/token\"");
        assertThat(JSON.readTree(document).path("components").path("securitySchemes").path("clientCredentials")
                .path("flows").path("clientCredentials").path("tokenUrl").asString()).isEqualTo("/api/v1/oauth2/token");
    }

    @Test
    void theClientCredentialsFlowIsDocumentedWithItsErrorsScopesAndNoHost() throws IOException {
        JsonNode scheme = JSON.readTree(Files.readString(PUBLIC)).path("components").path("securitySchemes")
                .path("clientCredentials");

        assertThat(scheme.path("type").asString()).isEqualTo("oauth2");
        String description = scheme.path("description").asString();
        assertThat(description).contains("invalid_client").contains("invalid_scope")
                .contains("temporarily_unavailable").contains("Retry-After").contains("There is no refresh token")
                .contains("5 to 60 minutes").contains("client_credentials").doesNotContainPattern("https?://");
        Set<String> scopes = new HashSet<>();
        scheme.path("flows").path("clientCredentials").path("scopes").properties()
                .forEach(scope -> scopes.add(scope.getKey()));
        assertThat(scopes).containsExactlyInAnyOrder("objects.read", "records.read", "records.write");
        assertThat(scheme.path("flows").size()).as("no other flow: no code, no implicit, no password").isEqualTo(1);
    }

    @Test
    void everyOperationNamesTheOneScopeItNeedsAndTheDescriptionNamesNone() throws IOException {
        JsonNode paths = JSON.readTree(Files.readString(PUBLIC)).path("paths");
        Set<String> known = Set.of("objects.read", "records.read", "records.write");

        paths.properties().forEach(path -> path.getValue().properties().forEach(method -> {
            String where = method.getKey() + " " + path.getKey();
            Set<String> scopes = new HashSet<>();
            method.getValue().path("security").forEach(requirement ->
                    requirement.path("clientCredentials").forEach(scope -> scopes.add(scope.asString())));
            if ("getPublicApiDescription".equals(method.getValue().path("operationId").asString())) {
                assertThat(scopes).as(where).isEmpty();
            } else {
                assertThat(scopes).as(where).hasSize(1).isSubsetOf(known);
            }
        }));
    }

    @Test
    void theScopesOfThePublicTableAreTheScopesOfTheSharedCatalogue() {
        assertThat(app.platform.sharedkernel.access.ApiScopes.ALL.stream()
                .map(app.platform.sharedkernel.access.ApiScopes.Scope::key).toList())
                .containsExactly("objects.read", "records.read", "records.write");
    }

    @Test
    void noSchemaOfTheInternalInterfaceAppearsApartFromTheSharedErrorModel() throws IOException {
        Set<String> internal = new HashSet<>();
        JSON.readTree(Files.readString(INTERNAL)).path("components").path("schemas").properties()
                .forEach(schema -> internal.add(schema.getKey()));
        Set<String> published = new HashSet<>();
        JSON.readTree(Files.readString(PUBLIC)).path("components").path("schemas").properties()
                .forEach(schema -> published.add(schema.getKey()));

        published.removeAll(SHARED);

        assertThat(published).as("schemas of the public contract that are also schemas of the internal one")
                .doesNotContainAnyElementsOf(internal);
        assertThat(published).as("every schema of the public contract has its own name").allMatch(name ->
                name.startsWith("Public") || name.startsWith("ApiResponse") || name.startsWith("ApiPageResponse"));
    }

    @Test
    void theContractNamesNoHostAndNoTimeOfGeneration() throws IOException {
        String document = Files.readString(PUBLIC);

        assertThat(document).doesNotContain("localhost").doesNotContain("generatedAt").doesNotContain("tenant-a")
                .doesNotContainPattern("https?://");
        JsonNode servers = JSON.readTree(document).path("servers");
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).path("url").asString()).isEqualTo("/");
    }

    @Test
    void theContractSpeaksToACustomerAndNotInTheVocabularyOfTheProject() throws IOException {
        String document = Files.readString(PUBLIC);

        assertThat(document).doesNotContain("Sprint ").doesNotContain("ADR-").doesNotContain("FA1")
                .doesNotContain("platformapi").doesNotContain("app.platform");
    }

    @Test
    void thereIsExactlyOneOperationForEachRowOfTheClosedTable() throws IOException {
        JsonNode paths = JSON.readTree(Files.readString(PUBLIC)).path("paths");
        Set<String> operations = new HashSet<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(method ->
                operations.add(method.getKey() + " " + path.getKey() + " " + method.getValue().path("operationId")
                        .asString())));

        assertThat(operations).containsExactlyInAnyOrder(
                "get /api/public/v1/openapi getPublicApiDescription",
                "get /api/public/v1/objects listObjects",
                "get /api/public/v1/objects/{object} describeObject",
                "post /api/public/v1/objects/{object}/records createRecord",
                "post /api/public/v1/objects/{object}/records/query queryRecords",
                "post /api/public/v1/objects/{object}/records/bulk bulkRecords",
                "get /api/public/v1/objects/{object}/records/{id} getRecord",
                "patch /api/public/v1/objects/{object}/records/{id} updateRecord",
                "delete /api/public/v1/objects/{object}/records/{id} deleteRecord",
                "post /api/public/v1/objects/{object}/records/{id}/restore restoreRecord");
    }

    @Test
    void everyOperationSaysWhichSecurityItNeedsAndTheDescriptionNeedsNone() throws IOException {
        JsonNode document = JSON.readTree(Files.readString(PUBLIC));

        assertThat(document.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(document.path("paths").path("/api/public/v1/openapi").path("get").path("security")).isEmpty();
        assertThat(document.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asString())
                .isEqualTo("bearer");
        assertThat(document.path("components").path("securitySchemes").size())
                .as("only what is true today: a bearer token and the client-credentials flow").isEqualTo(2);
        assertThat(document.path("components").path("securitySchemes").has("clientCredentials")).isTrue();
    }

    @Test
    void updateAsksForTheVersionInTheHeaderAndCreateAndBulkAcceptAKey() throws IOException {
        JsonNode paths = JSON.readTree(Files.readString(PUBLIC)).path("paths");

        assertThat(parameterNames(paths.path("/api/public/v1/objects/{object}/records/{id}").path("patch")))
                .contains("If-Match");
        assertThat(parameterNames(paths.path("/api/public/v1/objects/{object}/records").path("post")))
                .contains("Idempotency-Key");
        assertThat(parameterNames(paths.path("/api/public/v1/objects/{object}/records/bulk").path("post")))
                .contains("Idempotency-Key");
    }

    private static Set<String> parameterNames(JsonNode operation) {
        Set<String> names = new HashSet<>();
        operation.path("parameters").forEach(parameter -> names.add(parameter.path("name").asString()));
        return names;
    }
}
