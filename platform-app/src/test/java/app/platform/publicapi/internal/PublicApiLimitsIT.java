package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The limits of the public API, each at the limit and one above (Sprint 30, ADR-0245): the page size, the size of a
 * bulk call in records and in bytes, the size of a filter, the values of an {@code IN}, the depth of a filter, the scan
 * limit of an unindexed question, and the turns of an organization. Above a limit the answer is a refusal in the error
 * model that says which limit; nothing is read, written or slowed for the others.
 */
@PlatformIntegrationTest
class PublicApiLimitsIT extends PublicApiSupport {

    private Caller program() {
        Organization organization = organization();
        adminWithEmployee(organization);
        return administrator(organization);
    }

    private static String condition(String value) {
        return "{\"op\":\"EQ\",\"field\":\"name__c\",\"values\":[\"" + value + "\"]}";
    }

    private static String allOf(int conditions) {
        List<String> items = new ArrayList<>();
        for (int index = 0; index < conditions; index++) {
            items.add(condition("v" + index));
        }
        return "{\"filter\":{\"op\":\"OR\",\"items\":[" + String.join(",", items) + "]}}";
    }

    @Test
    void thePageSizeIsTwoHundredAtMostAndOneMoreIsRefusedAtItsPlace() {
        Caller program = program();
        newEmployee(program, "A", "Sales");

        Response atTheLimit = program.post(EMPLOYEE_RECORDS + "/query", "{\"limit\":200}");
        Response above = program.post(EMPLOYEE_RECORDS + "/query", "{\"limit\":201}");
        Response zero = program.post(EMPLOYEE_RECORDS + "/query", "{\"limit\":0}");

        assertThat(atTheLimit.status()).as(atTheLimit.body()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(atTheLimit.body(), "$.pagination.limit").intValue()).isEqualTo(200);
        assertThat(above.status()).isEqualTo(400);
        assertThat(JsonPath.<Map<String, Object>>read(above.body(), "$.error.fields")).containsKey("limit");
        assertThat(zero.status()).isEqualTo(400);
    }

    @Test
    void aBulkCallOfTwoHundredRecordsIsCarriedOutAndTwoHundredAndOneIsRefusedBeforeAnythingIsWritten() {
        Caller program = program();
        List<String> items = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            items.add("{\"fields\":{\"name__c\":\"Bulk" + index + "\"}}");
        }
        String atTheLimit = "{\"operation\":\"CREATE\",\"records\":[" + String.join(",", items) + "]}";
        items.add("{\"fields\":{\"name__c\":\"One too many\"}}");
        String above = "{\"operation\":\"CREATE\",\"records\":[" + String.join(",", items) + "]}";

        Response refused = program.post(EMPLOYEE_RECORDS + "/bulk", above);
        Response carriedOut = program.post(EMPLOYEE_RECORDS + "/bulk", atTheLimit);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<Map<String, Object>>read(refused.body(), "$.error.fields")).containsKey("records");
        assertThat(carriedOut.status()).as(carriedOut.body().substring(0, Math.min(300, carriedOut.body().length())))
                .isEqualTo(200);
        assertThat(JsonPath.<Number>read(carriedOut.body(), "$.data.succeeded").intValue()).isEqualTo(200);
        assertThat(program.post(EMPLOYEE_RECORDS + "/query", "{\"filter\":" + condition("One too many") + "}").body())
                .contains("\"data\":[]");
    }

    @Test
    void theBulkBodyLimitIsASettingAndAboveItTheCallIsRefusedBeforeItIsRead() {
        Caller program = program();
        String small = "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{\"name__c\":\"ok\"}}]}";
        String big = "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{\"notes__c\":\""
                + "x".repeat(3000) + "\"}}]}";
        try (TestApplication tight = TestApplication.start("platform.publicapi.limits.max-bulk-body-bytes=2048",
                "platform.publicapi.limits.max-body-bytes=1024")) {
            Caller onTight = program.onInstance(tight.port());

            Response atTheLimit = onTight.post(EMPLOYEE_RECORDS + "/bulk", small);
            Response above = onTight.post(EMPLOYEE_RECORDS + "/bulk", big);
            Response single = onTight.post(EMPLOYEE_RECORDS, createBody("{\"notes__c\":\"" + "x".repeat(1500)
                    + "\"}"));

            assertThat(atTheLimit.status()).as(atTheLimit.body()).isEqualTo(200);
            assertThat(above.status()).isEqualTo(413);
            assertThat(JsonPath.<String>read(above.body(), "$.error.code")).isEqualTo("PAYLOAD_TOO_LARGE");
            assertThat(single.status()).as("the single limit is its own setting").isEqualTo(413);
        }
    }

    @Test
    void aFilterOfTwentyConditionsIsAnsweredAndOneOfFiftyOneIsRefusedByTheEnginesOwnLimit() {
        Caller program = program();
        newEmployee(program, "A", "Sales");

        Response small = program.post(EMPLOYEE_RECORDS + "/query", allOf(20));
        Response above = program.post(EMPLOYEE_RECORDS + "/query", allOf(51));

        assertThat(small.status()).as(small.body()).isEqualTo(200);
        assertThat(above.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(above.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aFilterOfHundredAndOneNodesAndOneNestedTooDeepAreRefusedBeforeTheEngineReadsThem() {
        Caller program = program();
        String deep = condition("x");
        for (int level = 0; level < 40; level++) {
            deep = "{\"op\":\"AND\",\"items\":[" + deep + "]}";
        }

        Response tooDeep = program.post(EMPLOYEE_RECORDS + "/query", "{\"filter\":" + deep + "}");
        Response tooWide = program.post(EMPLOYEE_RECORDS + "/query", allOf(250));

        assertThat(tooDeep.status()).isEqualTo(400);
        assertThat(tooWide.status()).isEqualTo(400);
        assertThat(JsonPath.<Map<String, Object>>read(tooDeep.body(), "$.error.fields")).isNotEmpty();
    }

    @Test
    void anInListOfHundredValuesIsAnsweredAndOneOfHundredAndOneIsRefused() {
        Caller program = program();
        List<String> values = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            values.add("\"v" + index + "\"");
        }
        String at = "{\"filter\":{\"op\":\"IN\",\"field\":\"name__c\",\"values\":[" + String.join(",", values) + "]}}";
        values.add("\"one more\"");
        String above = "{\"filter\":{\"op\":\"IN\",\"field\":\"name__c\",\"values\":[" + String.join(",", values)
                + "]}}";

        assertThat(program.post(EMPLOYEE_RECORDS + "/query", at).status()).isEqualTo(200);
        assertThat(program.post(EMPLOYEE_RECORDS + "/query", above).status()).isEqualTo(400);
    }

    @Test
    void aQuestionThatWouldReadMoreThanTheScanLimitIsRefusedWithTheFieldToFlagAndOneAtTheLimitIsAnswered() {
        Caller program = program();
        for (int index = 1; index <= 3; index++) {
            newEmployee(program, "P" + index, "Sales");
        }
        try (TestApplication small = TestApplication.start("platform.data.query.unindexed-scan-limit=3")) {
            Caller onSmall = program.onInstance(small.port());
            String ask = "{\"filter\":" + condition("P1") + "}";

            Response atTheLimit = onSmall.post(EMPLOYEE_RECORDS + "/query", ask);
            newEmployee(program, "P4", "Sales");
            Response above = onSmall.post(EMPLOYEE_RECORDS + "/query", ask);

            assertThat(atTheLimit.status()).as(atTheLimit.body()).isEqualTo(200);
            assertThat(above.status()).as(above.body()).isEqualTo(400);
            assertThat(JsonPath.<Map<String, Object>>read(above.body(), "$.error.fields")).containsKey("query");
            assertThat(above.body()).contains("name__c");
        }
    }

    @Test
    void anOrganizationThatFloodsThePublicApiIsToldToWaitWithRetryAfterAndInTheErrorModel() throws Exception {
        Caller program = program();
        List<String> items = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            items.add("{\"fields\":{\"name__c\":\"Flood" + index + "\"}}");
        }
        String bulk = "{\"operation\":\"CREATE\",\"records\":[" + String.join(",", items) + "]}";
        try (TestApplication small = TestApplication.start("platform.tenancy.limits.max-concurrent=1",
                "platform.tenancy.limits.max-waiting=0", "platform.tenancy.limits.max-wait=50ms")) {
            Caller onSmall = program.onInstance(small.port());
            List<CompletableFuture<Response>> flood = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                flood.add(CompletableFuture.supplyAsync(() -> onSmall.post(EMPLOYEE_RECORDS + "/bulk", bulk)));
            }

            Response refused = null;
            for (CompletableFuture<Response> one : flood) {
                Response answer = one.get();
                assertThat(answer.status()).isIn(200, 429);
                if (answer.status() == 429) {
                    refused = answer;
                }
            }

            assertThat(refused).as("at least one of eight parallel bulk calls had to wait").isNotNull();
            assertThat(refused.header("Retry-After")).isPresent();
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("RATE_LIMITED");
            assertThat(refused.header("API-Version")).contains("v1");
        }
    }
}
