package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Sprint 15 integration tests share: an organization with a {@code Matrix__c} object that has one field of
 * each of the 19 field types (and a {@code Target__c} object for the links), the valid and the invalid value for each
 * type, and short ways to call the record endpoints of any object and to make members who hold exactly the field
 * permissions a test names. Everything goes through the real API; generic data only.
 */
public abstract class PipelineSupport extends RecordsSupport {

    protected static final String MATRIX = "/api/v1/objects/Matrix__c";
    protected static final String MATRIX_RECORDS = MATRIX + "/records";
    protected static final String MATRIX_BIN = MATRIX + "/recycle-bin";
    protected static final String TARGET_RECORDS = "/api/v1/objects/Target__c/records";

    /**
     * One field of the matrix object.
     *
     * @param name the name given when the field is made (the API name is {@code name__c})
     * @param type the field type
     * @param settings the JSON of the settings of the type, or empty
     * @param valid a JSON value that belongs in the field, or {@code null} for a field nobody writes, or a placeholder
     *        ({@code LINK}) for the identifier of a target record
     * @param invalid a JSON value that does not belong in the field
     * @param calculated whether the platform works the value out
     */
    protected record Case(String name, String type, String settings, String valid, String invalid,
            boolean calculated) {

        String api() {
            return name + "__c";
        }

        String key() {
            return "Matrix__c." + api();
        }
    }

    protected static final String LINK = "LINK";

    private static final String COLOURS = "\"values\":[{\"value\":\"red\",\"label\":\"red\",\"active\":true},"
            + "{\"value\":\"blue\",\"label\":\"blue\",\"active\":true},"
            + "{\"value\":\"grey\",\"label\":\"grey\",\"active\":false}]";

    /** The 19 types, one field each, in the order they are made. */
    protected static final List<Case> CASES = List.of(
            new Case("txt", "TEXT", ",\"settings\":{\"maxLength\":10}", "\"abc\"", "\"abcdefghijklmnop\"", false),
            new Case("ltx", "LONG_TEXT", ",\"settings\":{\"maxLength\":50}", "\"line one\\nline two\"",
                    "\"" + "x".repeat(51) + "\"", false),
            new Case("num", "NUMBER", ",\"settings\":{\"digits\":5}", "12345", "123456", false),
            new Case("dec", "DECIMAL", ",\"settings\":{\"precision\":6,\"scale\":2}", "1234.56", "1.234", false),
            new Case("cur", "CURRENCY", "", "10.50", "\"ten\"", false),
            new Case("pct", "PERCENT", "", "12.5", "12.345", false),
            new Case("flag", "BOOLEAN", "", "true", "\"yes\"", false),
            new Case("day", "DATE", "", "\"2030-01-31\"", "\"31/01/2030\"", false),
            new Case("stamp", "DATETIME", "", "\"2030-01-31T09:30:00Z\"", "\"yesterday\"", false),
            new Case("tod", "TIME", "", "\"09:30\"", "\"9.30pm\"", false),
            new Case("mail", "EMAIL", "", "\"user-a@example.test\"", "\"not an address\"", false),
            new Case("phone", "PHONE", "", "\"+1 555 0100\"", "\"call me\"", false),
            new Case("site", "URL", "", "\"https://example.test/a\"", "\"ftp://example.test\"", false),
            new Case("pick", "PICKLIST", ",\"settings\":{" + COLOURS + "}", "\"red\"", "\"green\"", false),
            new Case("many", "MULTI_PICKLIST", ",\"settings\":{" + COLOURS + "}", "[\"red\",\"blue\"]",
                    "[\"red\",\"red\"]", false),
            new Case("link", "LOOKUP", ",\"settings\":{\"targetObject\":\"Target__c\"}", LINK, "\"not-an-id\"",
                    false),
            new Case("parent", "MASTER_DETAIL", ",\"settings\":{\"targetObject\":\"Target__c\"}", LINK,
                    "\"not-an-id\"", false),
            new Case("calc", "FORMULA", ",\"settings\":{\"expression\":\"num__c * 2\",\"resultType\":\"DECIMAL\"}",
                    "7", "7", true),
            new Case("code", "AUTO_NUMBER", ",\"settings\":{\"prefix\":\"M-\",\"startAt\":1,\"width\":4}", "\"M-0099\"",
                    "\"M-0099\"", true));

    /** An organization with the matrix object and one target record. */
    protected record Matrix(Organization organization, TestBrowser admin, String targetId) {
    }

    protected Matrix matrix() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Target", "Targets");
        makeObject(admin, "Matrix", "Matrices");
        for (Case one : CASES) {
            Response created = admin.postJson(METADATA_OBJECTS + "/Matrix__c/fields", "{\"name\":\"" + one.name()
                    + "\",\"label\":\"" + one.name() + "\",\"type\":\"" + one.type() + "\"" + one.settings() + "}");
            assertThat(created.status()).as(one.name() + ": " + created.body()).isEqualTo(201);
        }
        Response target = admin.postJson(TARGET_RECORDS, "{\"fields\":{}}");
        assertThat(target.status()).as(target.body()).isEqualTo(201);
        return new Matrix(organization, admin, idOf(target));
    }

    protected static void makeObject(TestBrowser admin, String name, String plural) {
        Response object = admin.postJson(METADATA_OBJECTS, "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"pluralLabel\":\"" + plural + "\",\"description\":\"\"}");
        assertThat(object.status()).as(object.body()).isEqualTo(201);
    }

    /** The JSON value to write for the case: its valid value, with the identifier of a target for a link. */
    protected static String valid(Case one, String targetId) {
        return LINK.equals(one.valid()) ? "\"" + targetId + "\"" : one.valid();
    }

    /** All the values a record needs (the master-detail) plus every writable field, as the fields JSON. */
    protected static String everything(String targetId) {
        List<String> parts = new ArrayList<>();
        for (Case one : CASES) {
            if (!one.calculated()) {
                parts.add("\"" + one.api() + "\":" + valid(one, targetId));
            }
        }
        return "{" + String.join(",", parts) + "}";
    }

    protected static Response createIn(TestBrowser browser, String records, String fieldsJson) {
        return browser.postJson(records, body(fieldsJson));
    }

    protected static Response patchIn(TestBrowser browser, String records, String id, long version,
            String fieldsJson) {
        return browser.request("PATCH", records + "/" + id, update(version, fieldsJson));
    }

    protected static long currentVersion(TestBrowser browser, String records, String id) {
        return versionOf(browser.get(records + "/" + id));
    }

    /**
     * The version of one field of an object, read from the object (there is no single-field GET endpoint). Needed to
     * carry the version a field update is based on.
     */
    protected static long fieldVersion(TestBrowser admin, String objectApiName, String fieldApiName) {
        Response object = admin.get(METADATA_OBJECTS + "/" + objectApiName);
        List<Number> versions = JsonPath.read(object.body(),
                "$.data.fields[?(@.apiName=='" + fieldApiName + "')].version");
        return versions.get(0).longValue();
    }

    protected static Map<String, Object> fieldsOf(Response read) {
        return JsonPath.read(read.body(), "$.data.fields");
    }

    /** The first problem reported at the place, or {@code null} when none is. */
    protected static String problemAt(Response refused, String place) {
        try {
            List<String> texts = JsonPath.read(refused.body(), "$.error.fields['" + place + "']");
            return texts == null || texts.isEmpty() ? null : texts.get(0);
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    /** Every place the refusal reports a problem at. */
    protected static Set<String> placesOf(Response refused) {
        Map<String, Object> fields = JsonPath.read(refused.body(), "$.error.fields");
        return fields.keySet();
    }

    /** Field permissions for a member: one action on every field of the matrix object (read or edit). */

    protected static String grants(String action) {
        List<String> parts = new ArrayList<>();
        for (Case one : CASES) {
            // Nobody edits a calculated field; a member who "edits" the rest reads those.
            String given = one.calculated() && "edit".equals(action) ? "read" : action;
            parts.add("{\"key\":\"" + one.key() + "\",\"actions\":[\"" + given + "\"]}");
        }
        return "[" + String.join(",", parts) + "]";
    }

    /** A member who may use the matrix object and holds this action on all of its fields ({@code null}: none). */
    protected Holder matrixMember(Matrix matrix, String fieldAction) {
        String fields = fieldAction == null ? "[]" : grants(fieldAction);
        return holderWith(matrix.organization(), matrix.admin(), "Matrix__c", fields, "read", "create", "update",
                "delete");
    }
}
