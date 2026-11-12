package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The errors of the public API are the one error model, with stable codes and places (Sprint 30, ADR-0245, ADR-0247):
 * each code a caller can meet comes out once, a validation error says where, a malformed body is told by place and in
 * fixed words, and no value, key or exception text is ever in an answer.
 */
@PlatformIntegrationTest
class PublicApiErrorsIT extends PublicApiSupport {

    private Caller program() {
        Organization organization = organization();
        adminWithEmployee(organization);
        return administrator(organization);
    }

    private static void assertError(Response answer, int status, String code) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(status);
        assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo(code);
        assertThat(JsonPath.<String>read(answer.body(), "$.error.requestId")).isNotBlank();
        assertThat(answer.header("X-Request-Id")).contains(JsonPath.<String>read(answer.body(), "$.error.requestId"));
    }

    // ---- each stable code once ----

    @Test
    void validationErrorNamesThePlaceOfEveryProblemOfTheFirstStageThatFoundAny() {
        Caller program = program();

        Response answer = program.post(EMPLOYEE_RECORDS, createBody("{\"nothing__c\":1,\"salary__c\":\"text\"}"));

        assertError(answer, 400, "VALIDATION_ERROR");
        assertThat(JsonPath.<Map<String, Object>>read(answer.body(), "$.error.fields")).isNotEmpty();
        assertThat(answer.body()).doesNotContain("text\"").doesNotContain("Exception");
    }

    @Test
    void aNumberSentAsTextIsRefusedAtItsFieldAndNeverRoundedOrAccepted() {
        Caller program = program();

        Response asText = program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"A\",\"salary__c\":\"1250.50\"}"));
        Response asNumber = program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"A\",\"salary__c\":1250.50}"));

        assertError(asText, 400, "VALIDATION_ERROR");
        assertThat(JsonPath.<Map<String, Object>>read(asText.body(), "$.error.fields")).containsKey("salary__c");
        assertThat(asNumber.status()).isEqualTo(201);
        assertThat(asNumber.body()).contains("1250.50");
    }

    @Test
    void aDecimalWithMoreDigitsThanADoubleHoldsKeepsEveryDigitThroughCreateChangeAndRead() {
        Caller program = program();

        Response created = program.post(EMPLOYEE_RECORDS,
                createBody("{\"name__c\":\"A\",\"salary__c\":99999999999999.99}"));
        Response read = program.get(EMPLOYEE_RECORDS + "/" + id(created));
        Response changed = program.patch(EMPLOYEE_RECORDS + "/" + id(created), "{\"fields\":{\"salary__c\":"
                + "12345678901234.56}}", "If-Match", etag(read));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(read.body()).contains("99999999999999.99");
        assertThat(changed.body()).as(changed.body()).contains("12345678901234.56");
    }

    @Test
    void malformedRequestIsABodyThatIsNotJson() {
        Caller program = program();

        assertError(program.post(EMPLOYEE_RECORDS, "not json"), 400, "MALFORMED_REQUEST");
        assertError(program.post(EMPLOYEE_RECORDS, ""), 400, "MALFORMED_REQUEST");
        assertError(program.post(EMPLOYEE_RECORDS, "[1,2]"), 400, "MALFORMED_REQUEST");
    }

    @Test
    void unauthenticatedAndNotFoundAreTheTwoWaysOfNotBeingLetIn() {
        Caller program = program();

        assertError(program.withToken(null).get(OBJECTS), 401, "UNAUTHENTICATED");
        assertError(program.get(OBJECTS + "/Nothing__c"), 404, "NOT_FOUND");
        assertError(program.get(EMPLOYEE_RECORDS + "/" + java.util.UUID.randomUUID()), 404, "NOT_FOUND");
    }

    private static final String BODY_A = createBody("{\"name__c\":\"A\"}");

    @Test
    void forbiddenIsAMemberWhoMayReadButNotWrite() {
        Organization organization = organization();
        var admin = adminWithEmployee(organization);
        Caller reader = memberWith(organization, admin, "read");

        assertError(reader.post(EMPLOYEE_RECORDS, BODY_A), 403, "FORBIDDEN");
    }

    @Test
    void methodNotAllowedAndNotAcceptableAndUnsupportedMediaTypeAreTheErrorModelToo() {
        Caller program = program();
        String id = newEmployee(program, "A", "Sales");

        assertError(program.request("PUT", EMPLOYEE_RECORDS + "/" + id, "{}"), 405, "METHOD_NOT_ALLOWED");
        assertError(program.get(OBJECTS, "Accept", "application/xml"), 406, "NOT_ACCEPTABLE");
        assertError(program.request("POST", EMPLOYEE_RECORDS, "{}", "Content-Type", "text/plain"), 415,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void conflictIsADeleteOfWhatIsAlreadyDeletedAndConcurrentModificationIsAStaleVersion() {
        Caller program = program();
        String id = newEmployee(program, "A", "Sales");

        assertError(program.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"X\"}}",
                "If-Match", "\"7\""), 409, "CONCURRENT_MODIFICATION");
        program.delete(EMPLOYEE_RECORDS + "/" + id);
        assertError(program.delete(EMPLOYEE_RECORDS + "/" + id), 409, "CONFLICT");
        assertError(program.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"X\"}}",
                "If-Match", "\"0\""), 409, "CONFLICT");
    }

    // ---- the places ----

    @Test
    void aPropertyOfTheRequestThatTheContractDoesNotHaveIsRefusedWithItsPlace() {
        Caller program = program();
        String id = newEmployee(program, "A", "Sales");

        Response create = program.post(EMPLOYEE_RECORDS, "{\"fields\":{\"name__c\":\"A\"},\"colour\":\"red\"}");
        Response update = program.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{},\"version\":3}", "If-Match",
                "\"0\"");
        Response query = program.post(EMPLOYEE_RECORDS + "/query", "{\"sorting\":[]}");
        Response bulk = program.post(EMPLOYEE_RECORDS + "/bulk",
                "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{},\"extra\":1}]}");

        assertThat(JsonPath.<Map<String, Object>>read(create.body(), "$.error.fields")).containsOnlyKeys("colour");
        assertThat(JsonPath.<Map<String, Object>>read(update.body(), "$.error.fields")).containsOnlyKeys("version");
        assertThat(JsonPath.<Map<String, Object>>read(query.body(), "$.error.fields")).containsOnlyKeys("sorting");
        assertThat(JsonPath.<Map<String, Object>>read(bulk.body(), "$.error.fields"))
                .containsOnlyKeys("records[0].extra");
        for (Response refused : List.of(create, update, query, bulk)) {
            assertThat(refused.status()).isEqualTo(400);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void aQueryOfTheWrongShapeIsToldByPlace() {
        Caller program = program();

        Response wrongType = program.post(EMPLOYEE_RECORDS + "/query", "{\"limit\":\"ten\"}");
        Response unknownComparison = program.post(EMPLOYEE_RECORDS + "/query",
                "{\"filter\":{\"op\":\"LIKE\",\"field\":\"name__c\",\"values\":[\"a\"]}}");
        Response unknownField = program.post(EMPLOYEE_RECORDS + "/query",
                "{\"filter\":{\"op\":\"EQ\",\"field\":\"nothing__c\",\"values\":[\"a\"]}}");

        assertThat(JsonPath.<Map<String, Object>>read(wrongType.body(), "$.error.fields")).containsOnlyKeys("limit");
        assertError(unknownComparison, 400, "VALIDATION_ERROR");
        assertThat(JsonPath.<Map<String, Object>>read(unknownComparison.body(), "$.error.fields"))
                .containsOnlyKeys("filter.op");
        assertError(unknownField, 400, "VALIDATION_ERROR");
        assertThat(unknownField.body()).doesNotContain("Exception");
    }

    @Test
    void theHeadersOfAWriteAreCheckedWithFixedWords() {
        Caller program = program();
        String id = newEmployee(program, "A", "Sales");
        String path = EMPLOYEE_RECORDS + "/" + id;
        String body = "{\"fields\":{\"department__c\":\"X\"}}";

        Response missing = program.patch(path, body);
        Response malformed = program.patch(path, body, "If-Match", "3");
        Response star = program.patch(path, body, "If-Match", "*");
        Response secret = program.patch(path, body, "If-Match", "\"secret-text\"");

        for (Response refused : List.of(missing, malformed, star, secret)) {
            assertError(refused, 400, "VALIDATION_ERROR");
            assertThat(JsonPath.<Map<String, Object>>read(refused.body(), "$.error.fields"))
                    .containsOnlyKeys("If-Match");
        }
        assertThat(secret.body()).doesNotContain("secret-text");
    }

    @Test
    void anIdentifierThatIsNotAnIdentifierIsAValidationErrorAtItsPlace() {
        Caller program = program();

        Response answer = program.get(EMPLOYEE_RECORDS + "/not-an-id");

        assertError(answer, 400, "VALIDATION_ERROR");
        assertThat(JsonPath.<Map<String, Object>>read(answer.body(), "$.error.fields")).containsOnlyKeys("id");
    }

    @Test
    void aBulkCallOfAnUnknownOperationOrWithoutRecordsIsRefused() {
        Caller program = program();

        Response operation = program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"MERGE\",\"records\":[{}]}");
        Response none = program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"records\":[]}");

        assertThat(JsonPath.<Map<String, Object>>read(operation.body(), "$.error.fields"))
                .containsOnlyKeys("operation");
        assertError(none, 400, "VALIDATION_ERROR");
        assertThat(JsonPath.<Map<String, Object>>read(none.body(), "$.error.fields")).containsKey("records");
    }

    // ---- size ----

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsReadWhetherOrNotItsLengthWasAnnounced() {
        Caller program = program();
        String big = createBody("{\"name__c\":\"" + "x".repeat(1_100_000) + "\"}");

        Response announced = program.post(EMPLOYEE_RECORDS, big);
        Response chunked = new TestHttp(port).requestChunked("POST", EMPLOYEE_RECORDS,
                big.getBytes(StandardCharsets.UTF_8), "Host", program.host(), "Authorization",
                "Bearer " + program.token(), "Content-Type", "application/json");

        assertError(announced, 413, "PAYLOAD_TOO_LARGE");
        assertError(chunked, 413, "PAYLOAD_TOO_LARGE");
        assertThat(announced.body()).doesNotContain("xxxx");
    }

    // ---- nothing leaks ----

    @Test
    void noAnswerRepeatsATypedValueATokenOrALibrariesMessage() {
        Caller program = program();
        String typed = "typed-" + java.util.UUID.randomUUID();

        List<Response> refusals = List.of(
                program.post(EMPLOYEE_RECORDS, createBody("{\"salary__c\":\"" + typed + "\"}")),
                program.post(EMPLOYEE_RECORDS + "/query", "{\"filter\":{\"op\":\"EQ\",\"field\":\"" + typed
                        + "\",\"values\":[\"" + typed + "\"]}}"),
                program.post(EMPLOYEE_RECORDS, "{\"" + typed + "\":1}"),
                program.get(EMPLOYEE_RECORDS + "/" + typed),
                program.get(OBJECTS + "/" + typed),
                program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"" + typed + "\",\"records\":[{}]}"));

        for (Response refusal : refusals) {
            assertThat(refusal.body()).doesNotContain(typed).doesNotContain(program.token())
                    .doesNotContain("com.fasterxml").doesNotContain("tools.jackson").doesNotContain("at app.platform");
        }
    }
}
