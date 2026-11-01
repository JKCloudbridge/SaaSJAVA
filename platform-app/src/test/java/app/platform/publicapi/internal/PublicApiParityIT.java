package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Standard and custom objects behave identically through the public API (Sprint 30, ADR-0245): the same script runs
 * over
 * a custom object and over a standard one, and what comes out of each step has the same status and the same shape. An
 * object that a platform module owns is read-only to the record store and answers as one that does not exist.
 */
@PlatformIntegrationTest
class PublicApiParityIT extends PublicApiSupport {

    /** What the script needs to know about an object: its name and the fields to write, change and ask about. */
    private record Subject(String object, String create, String change, String filterField, String filterValue,
            String sortField, String badField) {
    }

    private static final Subject CUSTOM = new Subject("Employee__c",
            "{\"name__c\":\"Alpha\",\"department__c\":\"Sales\",\"salary__c\":10.50}",
            "{\"department__c\":\"Support\"}", "department__c", "Support", "name__c", "salary__c");
    private static final Subject STANDARD = new Subject("Account",
            "{\"name\":\"Alpha\",\"industry\":\"Retail\",\"annualRevenue\":10.50}",
            "{\"industry\":\"Banking\"}", "industry", "Banking", "name", "annualRevenue");

    /** One step of the script and what came out of it, without the values. */
    private record Step(String name, int status, List<String> shape) {
    }

    private static List<String> shapeOf(Response response) {
        if (response.body().isEmpty()) {
            return List.of();
        }
        Object parsed = JsonPath.parse(response.body()).json();
        TreeSet<String> keys = new TreeSet<>();
        if (parsed instanceof Map<?, ?> top) {
            top.forEach((key, value) -> {
                keys.add(String.valueOf(key));
                if (value instanceof Map<?, ?> inner) {
                    inner.keySet().stream().filter(name -> !"fields".equals(name))
                            .forEach(name -> keys.add(key + "." + name));
                }
            });
        }
        return new ArrayList<>(keys);
    }

    private List<Step> script(Caller program, Subject subject) {
        String records = OBJECTS + "/" + subject.object() + "/records";
        List<Step> steps = new ArrayList<>();
        Response described = program.get(OBJECTS + "/" + subject.object());
        steps.add(new Step("describe", described.status(), shapeOf(described)));
        Response created = program.post(records, createBody(subject.create()));
        steps.add(new Step("create", created.status(), shapeOf(created)));
        String id = id(created);
        Response read = program.get(records + "/" + id);
        steps.add(new Step("read", read.status(), shapeOf(read)));
        Response changed = program.patch(records + "/" + id, "{\"fields\":" + subject.change() + "}", "If-Match",
                etag(read));
        steps.add(new Step("change", changed.status(), shapeOf(changed)));
        Response stale = program.patch(records + "/" + id, "{\"fields\":" + subject.change() + "}", "If-Match",
                "\"0\"");
        steps.add(new Step("stale change", stale.status(), shapeOf(stale)));
        Response bad = program.post(records, createBody("{\"" + subject.badField() + "\":\"not a number\"}"));
        steps.add(new Step("bad create", bad.status(), List.of(JsonPath.<String>read(bad.body(), "$.error.code"))));
        Response query = program.post(records + "/query", "{\"filter\":{\"op\":\"EQ\",\"field\":\""
                + subject.filterField() + "\",\"values\":[\"" + subject.filterValue() + "\"]},\"sort\":[{\"field\":\""
                + subject.sortField() + "\"}],\"limit\":10}");
        steps.add(new Step("query", query.status(), shapeOf(query)));
        assertThat(JsonPath.<List<String>>read(query.body(), "$.data[*].id")).containsExactly(id);
        Response bulk = program.post(records + "/bulk", "{\"operation\":\"CREATE\",\"records\":[{\"fields\":"
                + subject.create() + "},{\"fields\":{\"" + subject.badField() + "\":\"x\"}}]}");
        steps.add(new Step("bulk", bulk.status(), shapeOf(bulk)));
        assertThat(JsonPath.<List<String>>read(bulk.body(), "$.data.results[*].status"))
                .containsExactly("CREATED", "FAILED");
        Response deleted = program.delete(records + "/" + id);
        steps.add(new Step("delete", deleted.status(), shapeOf(deleted)));
        Response gone = program.get(records + "/" + id);
        steps.add(new Step("read deleted", gone.status(), List.of(JsonPath.<String>read(gone.body(), "$.error.code"))));
        Response restored = program.post(records + "/" + id + "/restore", "");
        steps.add(new Step("restore", restored.status(), shapeOf(restored)));
        Response again = program.get(records + "/" + id);
        steps.add(new Step("read restored", again.status(), shapeOf(again)));
        return steps;
    }

    @Test
    void theSameScriptGivesTheSameStatusesAndShapesOnACustomAndAStandardObject() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);

        List<Step> custom = script(program, CUSTOM);
        List<Step> standard = script(program, STANDARD);

        assertThat(custom).hasSameSizeAs(standard);
        for (int index = 0; index < custom.size(); index++) {
            assertThat(standard.get(index).name()).isEqualTo(custom.get(index).name());
            assertThat(standard.get(index).status()).as(custom.get(index).name()).isEqualTo(custom.get(index).status());
            assertThat(standard.get(index).shape()).as(custom.get(index).name() + " has the same shape")
                    .isEqualTo(custom.get(index).shape());
        }
        assertThat(custom.stream().map(Step::status).toList())
                .containsExactly(200, 201, 200, 200, 409, 400, 200, 200, 204, 404, 200, 200);
    }

    @Test
    void bothKindsAreListedAndAreTheSameKindOfThing() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);

        Response listed = program.get(OBJECTS);

        assertThat(JsonPath.<List<String>>read(listed.body(), "$.data[*].apiName")).contains("Employee__c", "Account");
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.data[?(@.apiName=='Employee__c')].kind"))
                .containsExactly("CUSTOM");
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.data[?(@.apiName=='Account')].kind"))
                .containsExactly("STANDARD");
    }

    @Test
    void anObjectAPlatformModuleOwnsIsAbsentFromTheListAndNotFoundForEveryOperation() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        Response listed = program.get(OBJECTS);

        for (String owned : List.of("User", "Profile", "AccessPolicy", "Role", "Group")) {
            assertThat(JsonPath.<List<String>>read(listed.body(), "$.data[*].apiName")).doesNotContain(owned);
            for (Response answer : List.of(program.get(OBJECTS + "/" + owned),
                    program.post(OBJECTS + "/" + owned + "/records", createBody("{\"name\":\"x\"}")),
                    program.post(OBJECTS + "/" + owned + "/records/query", "{}"),
                    program.post(OBJECTS + "/" + owned + "/records/bulk",
                            "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{}}]}"))) {
                assertThat(answer.status()).as(owned).isEqualTo(404);
                assertThat(JsonPath.<String>read(answer.body(), "$.error.message"))
                        .as(owned + " reads like an object that does not exist")
                        .isEqualTo(JsonPath.<String>read(program.get(OBJECTS + "/Nothing__c").body(),
                                "$.error.message"));
            }
        }
    }

    @Test
    void theSameFieldOfAStandardObjectCanBeFilteredAndOrderedLikeACustomOne() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        Response described = program.get(OBJECTS + "/Account");

        List<String> operators = JsonPath.read(described.body(), "$.data.fields[?(@.apiName=='name')]"
                + ".filterOperators[*]");

        assertThat(operators).contains("EQ", "STARTS_WITH");
        assertThat(JsonPath.<List<Boolean>>read(described.body(), "$.data.fields[?(@.apiName=='name')].sortable"))
                .containsExactly(true);
    }
}
