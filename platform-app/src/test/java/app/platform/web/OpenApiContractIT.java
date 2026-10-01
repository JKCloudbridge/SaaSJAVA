package app.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp;
import app.platformapi.ApiPaths;
import app.platformapi.ErrorCode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The OpenAPI document is the contract between the API and its clients (ADR-0011). The committed copy in the API
 * contract module must equal what the running application generates: a controller change that is not reflected in the
 * committed document fails here, and the frontend's generated client (built from the committed copy, checked in CI)
 * can therefore never lag behind the API.
 *
 * <p>To accept a deliberate change, regenerate the committed document with {@code scripts/update-openapi.ps1}
 * (or {@code .sh}), which runs this test with {@code -Dopenapi.update=true}, and commit the result.
 */
@PlatformIntegrationTest
class OpenApiContractIT {

    private static final Path COMMITTED =
            Path.of("../platform-api-contract/src/main/resources/openapi/platform-api-v1.json");

    @LocalServerPort
    private int port;

    @Test
    void theCommittedDocumentEqualsWhatTheApplicationGenerates() throws IOException {
        String generated = normalize(generate());

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
            return;
        }
        assertThat(COMMITTED).as("run scripts/update-openapi to create the committed document").exists();
        String committed = normalize(Files.readString(COMMITTED, StandardCharsets.UTF_8));
        assertThat(committed)
                .as("The committed OpenAPI document is out of date. Regenerate it with scripts/update-openapi.ps1 "
                        + "(or .sh), review the change and commit it together with the frontend client.")
                .isEqualTo(generated);
    }

    @Test
    void generationIsDeterministic() {
        assertThat(generate()).isEqualTo(generate());
    }

    @Test
    void theDocumentFollowsTheApiConventions() {
        DocumentContext doc = JsonPath.parse(generate());

        assertThat(doc.<String>read("$.openapi")).startsWith("3.1");
        assertThat(doc.<List<Map<String, Object>>>read("$.servers"))
                .as("no host names: the document is identical in every environment")
                .containsExactly(Map.of("url", "/"));

        Map<String, Map<String, Map<String, Object>>> paths = doc.read("$.paths");
        assertThat(paths).isNotEmpty();
        paths.forEach((path, operations) -> {
            assertThat(path).startsWith(ApiPaths.V1 + "/");
            operations.forEach((method, operation) -> {
                assertThat(operation).as(method + " " + path).containsKey("operationId");
                assertThat(JsonPath.<String>read(operation, "$.responses.default.content['application/json']"
                        + ".schema['$ref']")).as(method + " " + path + " documents the error model")
                        .isEqualTo("#/components/schemas/ApiErrorResponse");
            });
        });
    }

    @Test
    void theSharedTypesEveryClientNeedsArePublished() {
        DocumentContext doc = JsonPath.parse(generate());

        assertThat(doc.<Map<String, Object>>read("$.components.schemas"))
                .containsKeys("ApiErrorResponse", "ApiError", "ErrorCode", "Pagination", "PlatformStatus",
                        "ApiResponsePlatformStatus");
        assertThat(doc.<List<String>>read("$.components.schemas.ErrorCode.enum"))
                .containsExactlyElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        assertThat(doc.<List<String>>read("$.components.schemas.ApiError.required"))
                .containsExactlyInAnyOrder("code", "message", "requestId");
        assertThat(doc.<List<String>>read("$.components.schemas.Pagination.required"))
                .containsExactlyInAnyOrder("limit", "hasMore");
    }

    private String generate() {
        TestHttp.Response response = new TestHttp(port).get(ApiPaths.OPENAPI);
        assertThat(response.status()).isEqualTo(200);
        return response.body();
    }

    /** Line feeds only and one final newline, so the file is identical on every operating system. */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n").stripTrailing() + "\n";
    }
}
