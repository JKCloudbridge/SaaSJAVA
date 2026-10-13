package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.PipelineSupport;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the tests of the index policy, the query engine and the record links share (Sprint 16): an organization with a
 * {@code Target__c} object and an {@code Item__c} object that has a field of every kind a query can use, short ways to
 * flag a field and wait for its index, to ask a question and to read the answer, all through the real API. The
 * background work is driven by the test ({@code workOn}), not waited for.
 */
abstract class QuerySupport extends PipelineSupport {

    protected static final String ITEMS = "/api/v1/objects/Item__c";
    protected static final String ITEM_RECORDS = ITEMS + "/records";
    protected static final String ITEM_QUERY = ITEM_RECORDS + "/query";
    protected static final String ITEM_BIN = ITEMS + "/recycle-bin";
    protected static final String ITEM_STATUS = ITEMS + "/index-status";
    protected static final String TARGETS = "/api/v1/objects/Target__c/records";

    /** The fields of {@code Item__c}: name, type and the JSON settings of the type. */
    protected static final Map<String, String[]> ITEM_FIELDS = new LinkedHashMap<>();

    static {
        ITEM_FIELDS.put("name", new String[] {"TEXT", ""});
        ITEM_FIELDS.put("code", new String[] {"TEXT", ""});
        ITEM_FIELDS.put("mail", new String[] {"EMAIL", ""});
        ITEM_FIELDS.put("stage", new String[] {"PICKLIST", ",\"settings\":{\"values\":[{\"value\":\"a\","
                + "\"label\":\"a\",\"active\":true},{\"value\":\"b\",\"label\":\"b\",\"active\":true},"
                + "{\"value\":\"c\",\"label\":\"c\",\"active\":true}]}"});
        ITEM_FIELDS.put("qty", new String[] {"NUMBER", ",\"settings\":{\"digits\":18}"});
        ITEM_FIELDS.put("price", new String[] {"DECIMAL", ",\"settings\":{\"precision\":18,\"scale\":6}"});
        ITEM_FIELDS.put("due", new String[] {"DATE", ""});
        ITEM_FIELDS.put("seen", new String[] {"DATETIME", ""});
        ITEM_FIELDS.put("done", new String[] {"BOOLEAN", ""});
        ITEM_FIELDS.put("link", new String[] {"LOOKUP", ",\"settings\":{\"targetObject\":\"Target__c\"}"});
        ITEM_FIELDS.put("notes", new String[] {"LONG_TEXT", ""});
        ITEM_FIELDS.put("calc", new String[] {"FORMULA", ",\"settings\":{\"expression\":\"qty__c * 2\","
                + "\"resultType\":\"NUMBER\"}"});
    }

    @Autowired
    protected IndexBackfill backfill;

    /** An organization with the two objects and an administrator who may do everything. */
    protected record Items(Organization organization, TestBrowser admin) {
    }

    protected Items items() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Target", "Targets");
        makeObject(admin, "Item", "Items");
        ITEM_FIELDS.forEach((name, definition) -> {
            Response created = admin.postJson(METADATA_OBJECTS + "/Item__c/fields", "{\"name\":\"" + name
                    + "\",\"label\":\"" + name + "\",\"type\":\"" + definition[0] + "\"" + definition[1] + "}");
            assertThat(created.status()).as(name + ": " + created.body()).isEqualTo(201);
        });
        return new Items(organization, admin);
    }

    /** Sets the flag of a field of {@code Item__c} (a live change by the administrator). */
    protected static Response flag(TestBrowser admin, String name, boolean indexed) {
        String[] definition = ITEM_FIELDS.get(name);
        long version = fieldVersion(admin, "Item__c", name + "__c");
        return admin.request("PUT", METADATA_OBJECTS + "/Item__c/fields/" + name + "__c", "{\"label\":\"" + name
                + "\",\"description\":\"\",\"indexed\":" + indexed + (definition[1].isEmpty() ? "" : definition[1])
                + ",\"version\":" + version + "}");
    }

    /** Sets whether a field of {@code Item__c} is unique (a live change by the administrator). */
    protected static Response setUnique(TestBrowser admin, String name, boolean unique) {
        String[] definition = ITEM_FIELDS.get(name);
        long version = fieldVersion(admin, "Item__c", name + "__c");
        return admin.request("PUT", METADATA_OBJECTS + "/Item__c/fields/" + name + "__c", "{\"label\":\"" + name
                + "\",\"description\":\"\",\"unique\":" + unique + (definition[1].isEmpty() ? "" : definition[1])
                + ",\"version\":" + version + "}");
    }

    /** The state of the enforcement of each unique field of {@code Item__c}, by field, as the status endpoint says. */
    protected static Map<String, String> uniquenessOf(TestBrowser admin) {
        Response status = admin.get(ITEM_STATUS);
        assertThat(status.status()).as(status.body()).isEqualTo(200);
        List<String> fields = JsonPath.read(status.body(), "$.data.uniqueness[*].field");
        List<String> states = JsonPath.read(status.body(), "$.data.uniqueness[*].state");
        Map<String, String> byField = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            byField.put(fields.get(index), states.get(index));
        }
        return byField;
    }

    /**
     * Runs the background work of the organization until every copy is complete and every unique field is enforced (or
     * gives up). A field whose state is in {@code except} is not waited for.
     */
    protected void settleUniqueness(Organization organization, TestBrowser admin, String... except) {
        List<String> skipped = List.of(except);
        for (int round = 0; round < 40; round++) {
            backfill.workOn(organization.id());
            List<String> states = JsonPath.read(admin.get(ITEM_STATUS).body(), "$.data.fields[*].state");
            boolean copies = states.stream().allMatch("FILLED"::equals);
            boolean enforced = uniquenessOf(admin).entrySet().stream()
                    .allMatch(entry -> "ENFORCED".equals(entry.getValue()) || skipped.contains(entry.getKey()));
            if (copies && enforced) {
                return;
            }
        }
        throw new AssertionError("uniqueness did not settle: " + admin.get(ITEM_STATUS).body());
    }

    protected static void flagAll(TestBrowser admin, String... names) {
        for (String name : names) {
            Response flagged = flag(admin, name, true);
            assertThat(flagged.status()).as(name + ": " + flagged.body()).isEqualTo(200);
        }
    }

    /** Runs the background work of the organization until no index is being filled or cleared (or gives up). */
    protected void settle(Organization organization, TestBrowser admin) {
        for (int round = 0; round < 40; round++) {
            backfill.workOn(organization.id());
            List<String> states = JsonPath.read(admin.get(ITEM_STATUS).body(), "$.data.fields[*].state");
            if (states.stream().noneMatch(state -> !"FILLED".equals(state))) {
                return;
            }
        }
        throw new AssertionError("the indexes did not settle: " + admin.get(ITEM_STATUS).body());
    }

    protected static Response query(TestBrowser browser, String json) {
        return browser.postJson(ITEM_QUERY, json);
    }

    /** A query with a filter, an order and a page size. */
    protected static String ask(String filter, String sort, int limit) {
        StringBuilder body = new StringBuilder("{");
        if (filter != null) {
            body.append("\"filter\":").append(filter).append(',');
        }
        if (sort != null) {
            body.append("\"sort\":").append(sort).append(',');
        }
        return body.append("\"limit\":").append(limit).append('}').toString();
    }

    /** A condition: {@code on("name__c", "EQ", "\"alpha\"")}. */
    protected static String on(String field, String op, String... values) {
        return "{\"op\":\"" + op + "\",\"field\":\"" + field + "\",\"values\":[" + String.join(",", values) + "]}";
    }

    protected static String group(String op, String... items) {
        return "{\"op\":\"" + op + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    protected static String by(String field, boolean descending) {
        return "[{\"field\":\"" + field + "\",\"descending\":" + descending + "}]";
    }

    protected static List<String> ids(Response answer) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return JsonPath.read(answer.body(), "$.data[*].record.id");
    }

    protected static boolean hasMore(Response answer) {
        return JsonPath.read(answer.body(), "$.pagination.hasMore");
    }

    protected static String next(Response answer) {
        return JsonPath.read(answer.body(), "$.pagination.nextCursor");
    }

    /** Every page of a query, followed by its cursors; returns the identifiers in the order they came. */
    protected static List<String> allPages(TestBrowser browser, String filter, String sort, int pageSize) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 500; page++) {
            String body = ask(filter, sort, pageSize);
            if (cursor != null) {
                body = body.substring(0, body.length() - 1) + ",\"cursor\":\"" + cursor + "\"}";
            }
            Response answer = query(browser, body);
            all.addAll(ids(answer));
            if (!hasMore(answer)) {
                return all;
            }
            cursor = next(answer);
        }
        throw new AssertionError("a query did not end");
    }

    /** Creates a record that must succeed and returns its identifier. */
    protected static String put(TestBrowser browser, String fieldsJson) {
        Response created = browser.postJson(ITEM_RECORDS, "{\"fields\":" + fieldsJson + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return idOf(created);
    }

    /** What the owner of the database sees in a slot column of a record (to prove the copy is in the row). */
    protected static String slotOf(String id, String column) {
        List<String> rows = owner("select " + column + "::text from object_record where id = ?::uuid", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
