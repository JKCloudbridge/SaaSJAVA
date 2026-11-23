package app.platform.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract of the public API only grows inside version 1 (Sprint 30, ADR-0247). The committed document is compared
 * with a baseline copy: a removed path, operation or status, a removed or retyped property, a property or parameter
 * that became required in a request, and a removed value of an enumeration fail the build, with the place. Additive
 * change passes. The detector is tested against deliberate breaks, so a pass means something.
 *
 * <p>When an addition is released, the baseline is replaced by the new committed document, in the same change as the
 * addition and the line of the ADR that names it. A break is never fixed by replacing the baseline: it is a new version
 * of the API beside this one.
 */
class PublicContractBaselineTest {

    private static final Path COMMITTED =
            Path.of("../platform-api-contract/src/main/resources/openapi/platform-public-api-v1.json");
    private static final Path BASELINE = Path.of("src/test/resources/openapi/platform-public-api-v1.baseline.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode read(Path file) throws IOException {
        return JSON.readTree(Files.readString(file));
    }

    @Test
    void theCommittedDocumentBreaksNothingThatTheBaselineAlreadyPromised() throws IOException {
        List<String> breaks = ContractBreaks.between(read(BASELINE), read(COMMITTED));

        assertThat(breaks).as("These changes would break software written against version 1 of the public API. "
                + "Make the change additive, or put it in a new version (ADR-0247).").isEmpty();
    }

    @Test
    void theBaselineIsTheCommittedDocumentUntilSomethingIsAdded() throws IOException {
        // Not an equality: an addition may be committed before the baseline is moved. But the baseline can never be
        // ahead of the document.
        assertThat(ContractBreaks.between(read(COMMITTED), read(BASELINE)).stream()
                .filter(sentence -> sentence.startsWith("path removed")).toList())
                .as("the baseline holds an address the document has not").isEmpty();
    }

    // ---- the detector itself ----

    private static JsonNode document() throws IOException {
        return read(COMMITTED);
    }

    private static tools.jackson.databind.node.ObjectNode copy() throws IOException {
        return (tools.jackson.databind.node.ObjectNode) document().deepCopy();
    }

    @Test
    void anUnchangedDocumentBreaksNothing() throws IOException {
        assertThat(ContractBreaks.between(document(), copy())).isEmpty();
    }

    @Test
    void aRemovedPathIsABreak() throws IOException {
        var changed = copy();
        ((tools.jackson.databind.node.ObjectNode) changed.get("paths")).remove("/api/public/v1/objects");

        assertThat(ContractBreaks.between(document(), changed)).contains("path removed: /api/public/v1/objects");
    }

    @Test
    void aRemovedOperationIsABreak() throws IOException {
        var changed = copy();
        ((tools.jackson.databind.node.ObjectNode) changed.get("paths")
                .get("/api/public/v1/objects/{object}/records/{id}"))
                .remove("patch");

        assertThat(ContractBreaks.between(document(), changed))
                .contains("operation removed: PATCH /api/public/v1/objects/{object}/records/{id}");
    }

    @Test
    void aRemovedStatusIsABreak() throws IOException {
        var changed = copy();
        ((tools.jackson.databind.node.ObjectNode) changed.get("paths").get("/api/public/v1/objects/{object}/records")
                .get("post").get("responses")).remove("409");

        assertThat(ContractBreaks.between(document(), changed))
                .contains("status removed: POST /api/public/v1/objects/{object}/records 409");
    }

    @Test
    void aRemovedPropertyIsABreak() throws IOException {
        var changed = copy();
        ((tools.jackson.databind.node.ObjectNode) changed.get("components").get("schemas").get("PublicRecord")
                .get("properties")).remove("version");

        assertThat(ContractBreaks.between(document(), changed))
                .contains("property removed: schema PublicRecord.version");
    }

    @Test
    void aRetypedPropertyIsABreak() throws IOException {
        var changed = copy();
        ((tools.jackson.databind.node.ObjectNode) changed.get("components").get("schemas").get("PublicRecord")
                .get("properties").get("version")).put("type", "string");

        assertThat(ContractBreaks.between(document(), changed))
                .contains("property retyped: schema PublicRecord.version");
    }

    @Test
    void aPropertyCalledDescriptionIsAPropertyAndItsRetypingIsSeen() throws IOException {
        var changed = copy();
        ((tools.jackson.databind.node.ObjectNode) changed.get("components").get("schemas").get("PublicField")
                .get("properties").get("description")).put("type", "integer");

        assertThat(ContractBreaks.between(document(), changed))
                .contains("property retyped: schema PublicField.description");
    }

    @Test
    void aNewlyRequiredRequestFieldIsABreakButANewlyRequiredResponseFieldIsNot() throws IOException {
        var changed = copy();
        var request = (tools.jackson.databind.node.ObjectNode) changed.get("components").get("schemas")
                .get("PublicUpdateRequest");
        request.putArray("required").add("recordType");
        var response = (tools.jackson.databind.node.ObjectNode) changed.get("components").get("schemas")
                .get("PublicRecord");
        response.withArray("required").add("deletedAt");

        List<String> breaks = ContractBreaks.between(document(), changed);

        assertThat(breaks).contains("request field newly required: schema PublicUpdateRequest.recordType");
        assertThat(breaks).noneMatch(sentence -> sentence.contains("PublicRecord"));
    }

    @Test
    void aRemovedEnumerationValueIsABreakButAnAddedOneIsNot() throws IOException {
        var changed = copy();
        var values = (tools.jackson.databind.node.ArrayNode) changed.get("components").get("schemas")
                .get("PublicBulkRequest").get("properties").get("operation").get("enum");
        assertThat(values).isNotNull();
        values.add("MERGE");

        assertThat(ContractBreaks.between(document(), changed)).isEmpty();

        values.remove(0);
        assertThat(ContractBreaks.between(document(), changed)).anyMatch(sentence ->
                sentence.startsWith("enum value removed"));
    }

    @Test
    void aNewRequiredParameterIsABreakAndANewOptionalOneIsNot() throws IOException {
        var changed = copy();
        var parameters = changed.get("paths").get("/api/public/v1/objects").get("get").withArray("parameters");
        var optional = parameters.addObject();
        optional.put("name", "expand").put("in", "query").put("required", false);

        assertThat(ContractBreaks.between(document(), changed)).isEmpty();

        optional.put("required", true);
        assertThat(ContractBreaks.between(document(), changed))
                .contains("new required parameter: GET /api/public/v1/objects query:expand");
    }

    @Test
    void aNewWayToGetATokenIsAnAdditionAndBreaksNothing() throws IOException {
        // Sprint 31 (ADR-0270): the client-credentials flow and the scope each operation names were added to a
        // document that already named bearer tokens. Adding a security scheme or a requirement takes nothing away.
        var changed = copy();
        var schemes = (tools.jackson.databind.node.ObjectNode) changed.get("components").get("securitySchemes");
        schemes.putObject("someNewFlow").put("type", "oauth2");
        ((tools.jackson.databind.node.ObjectNode) changed.get("paths").get("/api/public/v1/objects").get("get"))
                .putArray("security").addObject().putArray("someNewFlow").add("objects.read");

        assertThat(ContractBreaks.between(document(), changed)).isEmpty();
    }

    @Test
    void theCommittedDocumentOnlyAddedTheClientCredentialsFlowToTheBaseline() throws IOException {
        JsonNode baseline = read(BASELINE);
        JsonNode committed = read(COMMITTED);

        committed.path("paths").properties().forEach(path -> assertThat(baseline.path("paths").has(path.getKey()))
                .as("no address was added by the flow: " + path.getKey()).isTrue());
        assertThat(committed.path("components").path("securitySchemes").has("bearerAuth")).isTrue();
        assertThat(committed.path("components").path("securitySchemes").has("clientCredentials")).isTrue();
    }

    @Test
    void aNewOperationAndANewOptionalPropertyAreAdditive() throws IOException {
        var changed = copy();
        ((tools.jackson.databind.node.ObjectNode) changed.get("paths"))
                .putObject("/api/public/v1/objects/{object}/count")
                .putObject("get").put("operationId", "countRecords");
        ((tools.jackson.databind.node.ObjectNode) changed.get("components").get("schemas").get("PublicRecord")
                .get("properties")).putObject("labels").put("type", "object");

        assertThat(ContractBreaks.between(document(), changed)).isEmpty();
    }
}
