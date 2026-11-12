package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestSignIn;
import app.platformapi.ApiPaths;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The contract of the public API is a document, committed and compared (Sprint 30, ADR-0248): what the running
 * application generates for the public group must equal the committed copy, and the copy is what the application serves
 * to anyone, without a token, in every profile, while the internal document stays unserved where it is switched off.
 * This is the contract test the CI stage of the integration tests runs.
 *
 * <p>To accept a deliberate change, regenerate the committed document with {@code scripts/update-openapi} (it runs this
 * test with {@code -Dopenapi.update=true}), review the diff, and commit it. A change that removes or retypes something
 * is stopped by {@code PublicContractBaselineTest} on the way.
 */
@PlatformIntegrationTest
class PublicOpenApiContractIT {

    private static final Path COMMITTED =
            Path.of("../platform-api-contract/src/main/resources/openapi/platform-public-api-v1.json");

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
        assertThat(normalize(Files.readString(COMMITTED, StandardCharsets.UTF_8)))
                .as("The committed public OpenAPI document is out of date. Regenerate it with scripts/update-openapi "
                        + "and review the change. If it removes or retypes something, that is a breaking change: it "
                        + "needs a new version of the API (ADR-0247), not a new baseline.")
                .isEqualTo(generated);
    }

    @Test
    void generationIsDeterministic() {
        assertThat(generate()).isEqualTo(generate());
    }

    @Test
    void theDocumentFollowsTheContractConventions() {
        DocumentContext doc = JsonPath.parse(generate());

        assertThat(doc.<String>read("$.openapi")).startsWith("3.1");
        assertThat(doc.<String>read("$.info.version")).isEqualTo("v1");
        assertThat(doc.<List<Map<String, Object>>>read("$.servers"))
                .as("no host names: the document is identical in every environment")
                .containsExactly(Map.of("url", "/"));
        assertThat(doc.<Map<String, Object>>read("$.components.securitySchemes.bearerAuth"))
                .containsEntry("type", "http").containsEntry("scheme", "bearer");
        assertThat(doc.<Map<String, Object>>read("$.components.schemas"))
                .containsKeys("ApiErrorResponse", "ApiError", "ErrorCode", "Pagination", "PublicRecord");

        Map<String, Map<String, Map<String, Object>>> paths = doc.read("$.paths");
        assertThat(paths.keySet()).as("exactly the operations of the closed table")
                .containsExactlyInAnyOrder(
                        "/api/public/v1/openapi",
                        "/api/public/v1/objects",
                        "/api/public/v1/objects/{object}",
                        "/api/public/v1/objects/{object}/records",
                        "/api/public/v1/objects/{object}/records/query",
                        "/api/public/v1/objects/{object}/records/bulk",
                        "/api/public/v1/objects/{object}/records/{id}",
                        "/api/public/v1/objects/{object}/records/{id}/restore");
        paths.forEach((path, operations) -> operations.forEach((method, operation) -> {
            String where = method + " " + path;
            assertThat(operation).as(where).containsKey("operationId");
            Map<String, Object> responses = JsonPath.read(operation, "$.responses");
            assertThat(responses).as(where + " documents the error model").containsKeys("default", "404", "429",
                    "500", "503");
            if (!ApiPaths.PUBLIC_OPENAPI.equals(path)) {
                assertThat(responses).as(where + " documents a missing or bad token").containsKey("401");
            }
            responses.forEach((status, response) -> assertThat(JsonPath.<Map<String, Object>>read(response,
                    "$.headers")).as(where + " " + status + " names the headers every answer carries")
                    .containsKeys("X-Request-ID", "API-Version"));
        }));
    }

    @Test
    void theDocumentIsServedToAnyoneWithoutATokenAndIsTheCommittedCopy() throws IOException {
        assumeNotUpdating();
        TestHttp http = new TestHttp(port);
        String committed = Files.readString(COMMITTED, StandardCharsets.UTF_8);

        for (String host : new String[] {TestSignIn.PLATFORM_HOST, "tenant-a.localhost"}) {
            TestHttp.Response served = http.get(ApiPaths.PUBLIC_OPENAPI, "Host", host);

            assertThat(served.status()).as(host).isEqualTo(200);
            assertThat(normalize(served.body())).as(host).isEqualTo(normalize(committed));
            assertThat(served.header("Content-Type")).hasValueSatisfying(value -> assertThat(value)
                    .startsWith("application/json"));
            assertThat(served.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                    .contains("public").contains("max-age"));
            assertThat(served.header("API-Version")).contains("v1");
        }
    }

    @Test
    void aCacheThatHoldsTheDocumentIsToldItHasNotChanged() {
        assumeNotUpdating();
        TestHttp http = new TestHttp(port);
        TestHttp.Response first = http.get(ApiPaths.PUBLIC_OPENAPI, "Host", TestSignIn.PLATFORM_HOST);
        String etag = first.header("ETag").orElseThrow();

        TestHttp.Response again = http.get(ApiPaths.PUBLIC_OPENAPI, "Host", TestSignIn.PLATFORM_HOST, "If-None-Match",
                etag);

        assertThat(again.status()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
    }

    @Test
    void theDocumentShowsShapesAndWordsAndNamesNothingOfAnyHostOrTime() {
        String document = generate();

        assertThat(document).doesNotContain("localhost").doesNotContain("tenant-a").doesNotContain("generatedAt");
    }

    @Test
    void whereTheGeneratorIsSwitchedOffTheInternalDocumentIsGoneButThePublicOneIsStillServed() {
        assumeNotUpdating();
        try (TestApplication deployed = TestApplication.start("springdoc.api-docs.enabled=false")) {
            TestHttp http = new TestHttp(deployed.port());

            assertThat(http.get(ApiPaths.OPENAPI, "Host", TestSignIn.PLATFORM_HOST).status())
                    .as("the internal document is not served where the generator is off").isEqualTo(404);
            TestHttp.Response served = http.get(ApiPaths.PUBLIC_OPENAPI, "Host", TestSignIn.PLATFORM_HOST);
            assertThat(served.status()).as("the published copy is").isEqualTo(200);
            assertThat(normalize(served.body())).contains("\"title\" : \"Public API\"");
        }
    }

    @Test
    void whenThePublicApiIsSwitchedOffTheDescriptionIsGoneToo() {
        try (TestApplication off = TestApplication.start("platform.publicapi.enabled=false")) {
            TestHttp http = new TestHttp(off.port());

            for (String path : new String[] {ApiPaths.PUBLIC_OPENAPI, ApiPaths.PUBLIC_OBJECTS,
                ApiPaths.PUBLIC_OBJECTS + "/Employee__c/records/query"}) {
                TestHttp.Response answer = http.get(path, "Host", "tenant-a.localhost");
                assertThat(answer.status()).as(path).isEqualTo(404);
                assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo("NOT_FOUND");
            }
            assertThat(http.get(ApiPaths.OPENAPI, "Host", TestSignIn.PLATFORM_HOST).status())
                    .as("the internal interface is not affected").isEqualTo(200);
        }
    }

    /**
     * What the generator writes for the public addresses: an instance whose generator is pointed at the public prefix
     * (the generator has one list of paths for the whole application, so the internal document and this one are two
     * runs of it). Generated once for all the tests of the class.
     */
    private static synchronized String generate() {
        if (generated == null) {
            try (TestApplication generator = TestApplication.start("springdoc.paths-to-match=/api/public/v1/**",
                    "platform.openapi.reachable-schemas-only=true")) {
                TestHttp.Response response = new TestHttp(generator.port()).get(ApiPaths.OPENAPI);
                assertThat(response.status()).as(response.body()).isEqualTo(200);
                generated = response.body();
            }
        }
        return generated;
    }

    private static String generated;

    /** While the committed copy is being rewritten, what is served (the copy on the class path) is the old one. */
    private static void assumeNotUpdating() {
        Assumptions.assumeFalse(Boolean.getBoolean("openapi.update"), "the committed copy is being rewritten");
    }

    /** Line feeds only and one final newline, so the file is identical on every operating system. */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n").stripTrailing() + "\n";
    }
}
