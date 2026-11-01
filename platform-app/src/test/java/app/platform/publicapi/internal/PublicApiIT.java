package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The exit test of Sprint 30 (ADR-0244 to ADR-0247): a program that holds only a bearer token creates, reads, changes
 * with the version, queries across pages, bulk-writes with a partial failure, deletes and restores records of a custom
 * object through the public interface, and every answer carries the request ID and the version of the interface.
 */
@PlatformIntegrationTest
class PublicApiIT extends PublicApiSupport {

    @Test
    void aProgramWithATokenWorksWithTheRecordsOfAnObjectFromTheFirstCreateToTheRestore() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);

        // describe
        Response objects = program.get(OBJECTS);
        assertThat(objects.status()).as(objects.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(objects.body(), "$.data[*].apiName")).contains("Employee__c");
        Response described = program.get(EMPLOYEES_API);
        assertThat(described.status()).as(described.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(described.body(), "$.data.fields[?(@.apiName=='name__c')]"
                + ".filterOperators[*]")).contains("EQ", "STARTS_WITH");
        assertThat(JsonPath.<Boolean>read(described.body(), "$.data.canCreate")).isTrue();

        // create
        Response created = program.post(EMPLOYEE_RECORDS, createBody(
                "{\"name__c\":\"A. Example\",\"department__c\":\"Sales\",\"salary__c\":1250.50}"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = id(created);
        assertThat(created.header("Location")).contains(EMPLOYEE_RECORDS + "/" + id);
        assertThat(etag(created)).isEqualTo("\"0\"");
        assertThat(JsonPath.<String>read(created.body(), "$.data.object")).isEqualTo("Employee__c");
        assertThat(created.body()).contains("1250.50");

        // read
        Response read = program.get(EMPLOYEE_RECORDS + "/" + id);
        assertThat(read.status()).isEqualTo(200);
        assertThat(etag(read)).isEqualTo("\"0\"");
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields.name__c")).isEqualTo("A. Example");

        // change with the version read
        Response changed = program.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"Support\"}}",
                "If-Match", etag(read));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(etag(changed)).isEqualTo("\"1\"");
        assertThat(JsonPath.<String>read(changed.body(), "$.data.fields.department__c")).isEqualTo("Support");
        assertThat(JsonPath.<String>read(changed.body(), "$.data.fields.name__c")).isEqualTo("A. Example");

        // a stale version loses, and nothing changes
        Response stale = program.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"Lost\"}}",
                "If-Match", "\"0\"");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(JsonPath.<String>read(program.get(EMPLOYEE_RECORDS + "/" + id).body(),
                "$.data.fields.department__c")).isEqualTo("Support");

        // query with a filter, an order and a cursor across pages
        for (int index = 1; index <= 5; index++) {
            newEmployee(program, "Person " + index, "Ops");
        }
        String filter = "{\"filter\":{\"op\":\"EQ\",\"field\":\"department__c\",\"values\":[\"Ops\"]},"
                + "\"sort\":[{\"field\":\"name__c\",\"descending\":true}]}";
        Response firstPage = program.post(EMPLOYEE_RECORDS + "/query",
                filter.substring(0, filter.length() - 1) + ",\"limit\":2}");
        assertThat(firstPage.status()).as(firstPage.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(firstPage.body(), "$.data[*].fields.name__c"))
                .containsExactly("Person 5", "Person 4");
        assertThat(JsonPath.<Boolean>read(firstPage.body(), "$.pagination.hasMore")).isTrue();
        List<String> all = queried(program, "Employee__c", filter, 2);
        assertThat(all).hasSize(5).doesNotHaveDuplicates().doesNotContain(id);

        // bulk with a partial failure, in input order
        Response bulk = program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"records\":["
                + "{\"fields\":{\"name__c\":\"Bulk 1\"}},{\"fields\":{\"salary__c\":\"not a number\"}},"
                + "{\"fields\":{\"name__c\":\"Bulk 3\"}}]}");
        assertThat(bulk.status()).as(bulk.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(bulk.body(), "$.data.results[*].status"))
                .containsExactly("CREATED", "FAILED", "CREATED");
        assertThat(JsonPath.<String>read(bulk.body(), "$.data.results[1].error.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(bulk.body()).doesNotContain("not a number");
        assertThat(JsonPath.<Boolean>read(bulk.body(), "$.data.committed")).isTrue();

        // delete, then it is gone, then restore
        Response deleted = program.delete(EMPLOYEE_RECORDS + "/" + id);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(program.get(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(program.delete(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(409);
        Response restored = program.post(EMPLOYEE_RECORDS + "/" + id + "/restore", "");
        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(program.get(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(200);
    }

    @Test
    void everyAnswerCarriesTheRequestIdAndTheVersionOfTheInterface() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        String id = newEmployee(program, "A", "Sales");

        List<Response> answers = List.of(program.get(OBJECTS), program.get(EMPLOYEES_API),
                program.get(EMPLOYEE_RECORDS + "/" + id), program.get(EMPLOYEE_RECORDS + "/" + key()),
                program.post(EMPLOYEE_RECORDS + "/query", "{}"), program.post(EMPLOYEE_RECORDS, "not json"),
                program.get(OBJECTS + "/Nothing__c"), program.withToken(null).get(OBJECTS),
                program.withToken("not-a-token").get(OBJECTS), program.get(API + "/no-such-address"),
                anonymous(organization.host()).get(API + "/openapi"));

        for (Response answer : answers) {
            assertThat(answer.header("X-Request-Id")).as("request id of " + answer.status()).isPresent();
            assertThat(answer.header("API-Version")).as("version of " + answer.status()).contains("v1");
        }
    }

    @Test
    void aRequestIdTheCallerSendsIsEchoed() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);

        Response answer = program.get(OBJECTS, "X-Request-Id", "my-request-0001");

        assertThat(answer.header("X-Request-Id")).contains("my-request-0001");
    }

    @Test
    void anAnswerWithRecordsIsNeverStoredByACache() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        String id = newEmployee(program, "A", "Sales");

        assertThat(program.get(EMPLOYEE_RECORDS + "/" + id).header("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(program.post(EMPLOYEE_RECORDS + "/query", "{}").header("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(program.get(OBJECTS).header("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
    }

    @Test
    void anAbsentValueIsAbsentNeverNullAndAProjectionBringsOnlyTheFieldsAsked() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        String id = newEmployee(program, "A", "Sales");

        Response answer = program.post(EMPLOYEE_RECORDS + "/query", "{\"fields\":[\"name__c\",\"notes__c\"]}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(answer.body(), "$.data[0].id")).isEqualTo(id);
        assertThat(answer.body()).doesNotContain("null").doesNotContain("notes__c");
    }
}
