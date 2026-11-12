package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The public door and the internal door give the same answer for the same member (Sprint 30, ADR-0245): for members
 * with different profiles, field rights and a private sharing default, the records each can reach, the fields each
 * sees and the refusals each meets are the same on both. There is one house; this proves the new door opens into it.
 */
@PlatformIntegrationTest
class PublicApiMatchesInternalIT extends PublicApiSupport {

    private static final String INTERNAL_QUERY = RECORDS + "/query";

    private List<String> internalIds(TestBrowser browser) {
        Response page = browser.postJson(INTERNAL_QUERY, "{\"limit\":200}");
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data[*].record.id");
    }

    @Test
    void membersWithDifferentRightsSeeAndMeetTheSameThroughBothDoors() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        Caller adminCaller = caller(organization.host(), admin);

        // Private by default: a record is reached by its owner and by those it is shared with.
        Response current = admin.get("/api/v1/sharing/objects/Employee__c");
        boolean configured = JsonPath.read(current.body(), "$.data.configured");
        String version = configured ? ",\"version\":" + JsonPath.<Number>read(current.body(), "$.data.version") : "";
        assertThat(admin.request("PUT", "/api/v1/sharing/objects/Employee__c",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":false" + version + "}").status()).isEqualTo(200);

        String narrow = "[{\"key\":\"Employee__c.name__c\",\"actions\":[\"edit\"]},"
                + "{\"key\":\"Employee__c.department__c\",\"actions\":[\"read\"]}]";
        Map<String, Caller> members = new LinkedHashMap<>();
        members.put("administrator", adminCaller);
        members.put("reader", memberWith(organization, admin, "read"));
        members.put("writer", memberWith(organization, admin, "read", "create", "update", "delete"));
        Holder limited = holderWith(organization, admin, "Employee__c", narrow, "read", "create", "update");
        members.put("limited", caller(organization.host(), as(organization, limited.member())));
        members.put("nobody", memberWith(organization, admin));

        // Every member that can create makes a record of their own; the administrator makes one with a salary.
        List<String> all = new ArrayList<>();
        all.add(id(adminCaller.post(EMPLOYEE_RECORDS, createBody(
                "{\"name__c\":\"Admin's\",\"department__c\":\"Sales\",\"salary__c\":10.50,"
                        + "\"notes__c\":\"private\"}"))));
        for (String name : List.of("writer", "limited")) {
            Response made = members.get(name).post(EMPLOYEE_RECORDS, createBody(
                    "{\"name__c\":\"" + name + "'s\"}"));
            assertThat(made.status()).as(name + ": " + made.body()).isEqualTo(201);
            all.add(id(made));
        }

        for (Map.Entry<String, Caller> member : members.entrySet()) {
            String who = member.getKey();
            Caller publicDoor = member.getValue();
            TestBrowser internalDoor = publicDoor.browser();

            // the same records
            Response publicPage = publicDoor.post(EMPLOYEE_RECORDS + "/query", "{\"limit\":200}");
            if ("nobody".equals(who)) {
                assertThat(publicPage.status()).as(who).isEqualTo(404);
                assertThat(internalDoor.postJson(INTERNAL_QUERY, "{}").status()).as(who).isEqualTo(404);
                continue;
            }
            assertThat(new TreeSet<>(JsonPath.<List<String>>read(publicPage.body(), "$.data[*].id")))
                    .as(who + " reaches the same records").isEqualTo(new TreeSet<>(internalIds(internalDoor)));

            // the same answer to a read of each record, with the same fields
            for (String id : all) {
                Response publicRead = publicDoor.get(EMPLOYEE_RECORDS + "/" + id);
                Response internalRead = internalDoor.get(RECORDS + "/" + id);
                assertThat(publicRead.status()).as(who + " reads " + id).isEqualTo(internalRead.status());
                if (publicRead.status() == 200) {
                    Map<String, Object> publicFields = JsonPath.read(publicRead.body(), "$.data.fields");
                    Map<String, Object> internalFields = JsonPath.read(internalRead.body(), "$.data.fields");
                    assertThat(new TreeSet<>(publicFields.keySet())).as(who + " sees the same fields of " + id)
                            .isEqualTo(new TreeSet<>(internalFields.keySet()));
                    assertThat(publicFields).as(who + " sees the same values").isEqualTo(internalFields);
                    assertThat(JsonPath.<Number>read(publicRead.body(), "$.data.version").longValue())
                            .isEqualTo(JsonPath.<Number>read(internalRead.body(), "$.data.version").longValue());
                }
            }

            // the same description of the object
            Response publicObject = publicDoor.get(EMPLOYEES_API);
            Response internalForm = internalDoor.get("/api/v1/objects/Employee__c/form");
            assertThat(new TreeSet<>(JsonPath.<List<String>>read(publicObject.body(), "$.data.fields[*].apiName")))
                    .as(who + " is told of the same fields")
                    .isEqualTo(new TreeSet<>(JsonPath.<List<String>>read(internalForm.body(),
                            "$.data.fields[*].apiName")));
            assertThat(JsonPath.<Boolean>read(publicObject.body(), "$.data.canUpdate"))
                    .isEqualTo(JsonPath.<Boolean>read(internalForm.body(), "$.data.actions.update"));
        }
    }

    @Test
    void theSameChangesAreRefusedAndAllowedInTheSameWordsThroughBothDoors() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        Caller adminCaller = caller(organization.host(), admin);
        Caller reader = memberWith(organization, admin, "read");
        Caller writer = memberWith(organization, admin, "read", "create", "update", "delete");
        String theirs = id(adminCaller.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Theirs\"}")));
        String change = "{\"fields\":{\"department__c\":\"X\"}}";

        for (Map.Entry<String, Caller> member : Map.of("reader", reader, "writer", writer).entrySet()) {
            Caller publicDoor = member.getValue();
            TestBrowser internalDoor = publicDoor.browser();

            Response internalChange = internalDoor.request("PATCH", RECORDS + "/" + theirs,
                    "{\"version\":0,\"fields\":{\"department__c\":\"X\"}}");
            // The internal change may have moved the version: the public one names the version it left.
            String etag = internalChange.status() == 200 ? "\"1\"" : "\"0\"";
            Response publicChange = publicDoor.patch(EMPLOYEE_RECORDS + "/" + theirs, change, "If-Match", etag);
            assertThat(publicChange.status()).as(member.getKey() + " change").isEqualTo(internalChange.status());
            if (publicChange.status() >= 400) {
                assertThat(JsonPath.<String>read(publicChange.body(), "$.error.code"))
                        .isEqualTo(JsonPath.<String>read(internalChange.body(), "$.error.code"));
                assertThat(JsonPath.<String>read(publicChange.body(), "$.error.message"))
                        .isEqualTo(JsonPath.<String>read(internalChange.body(), "$.error.message"));
            }

            Response internalCreate = internalDoor.postJson(RECORDS, "{\"fields\":{\"salary__c\":\"x\"}}");
            Response publicCreate = publicDoor.post(EMPLOYEE_RECORDS, createBody("{\"salary__c\":\"x\"}"));
            assertThat(publicCreate.status()).as(member.getKey() + " create").isEqualTo(internalCreate.status());
            assertThat(JsonPath.<String>read(publicCreate.body(), "$.error.code"))
                    .isEqualTo(JsonPath.<String>read(internalCreate.body(), "$.error.code"));
            assertThat(JsonPath.<Object>read(publicCreate.body(), "$.error"))
                    .as(member.getKey() + " is told the same places")
                    .isEqualTo(withoutIds(JsonPath.<Map<String, Object>>read(internalCreate.body(), "$.error"),
                            JsonPath.<Map<String, Object>>read(publicCreate.body(), "$.error")));
        }
    }

    /** The error of the internal answer with the identifiers that differ by request taken from the public one. */
    private static Map<String, Object> withoutIds(Map<String, Object> internal, Map<String, Object> pub) {
        Map<String, Object> copy = new LinkedHashMap<>(internal);
        copy.put("requestId", pub.get("requestId"));
        copy.put("traceId", pub.get("traceId"));
        if (!pub.containsKey("traceId")) {
            copy.remove("traceId");
        }
        return copy;
    }
}
