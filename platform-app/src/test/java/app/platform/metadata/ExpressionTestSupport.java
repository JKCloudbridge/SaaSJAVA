package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;

/**
 * What the Sprint 13 integration tests share (formula fields, validation rules, the check and try-it endpoints): an
 * organization with an {@code Order__c} object that has a number, a currency, a text, a date and a checkbox field, and
 * short ways to make formulas and rules through the real API.
 */
abstract class ExpressionTestSupport extends DefinitionsSupport {

    protected static final String ORDER = OBJECTS + "/Order__c";
    protected static final String RULES = ORDER + "/validation-rules";
    protected static final String EXPRESSIONS = "/api/v1/metadata/expressions";
    protected static final String FORMULA_REPORT = "/api/v1/metadata/formula-report";

    /** An organization with its administrator and {@code Order__c}: quantity, price, status, close date, active. */
    protected TestBrowser adminWithOrder(Organization organization) {
        TestBrowser admin = adminOf(organization);
        createObject(admin, "Order");
        for (String field : List.of(typedField("quantity", "NUMBER"), typedField("unitPrice", "CURRENCY"),
                typedField("status", "TEXT"), typedField("closeDate", "DATE"), typedField("active", "BOOLEAN"),
                typedField("note", "TEXT"))) {
            Response created = createField(admin, "Order__c", field);
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
        return admin;
    }

    protected TestBrowser adminWithOrder() {
        return adminWithOrder(organization());
    }

    protected static String typedField(String name, String type) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\"" + type + "\"}";
    }

    // ---- formulas ----

    protected static String formulaField(String name, String expression, String resultType) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\"FORMULA\",\"settings\":{"
                + "\"expression\":\"" + expression + "\",\"resultType\":\"" + resultType + "\"}}";
    }

    protected static Response createFormula(TestBrowser admin, String name, String expression, String resultType) {
        return createField(admin, "Order__c", formulaField(name, expression, resultType));
    }

    protected static void formulaExists(TestBrowser admin, String name, String expression, String resultType) {
        Response created = createFormula(admin, name, expression, resultType);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    /** The request that changes a formula field, carrying the version that was read. */
    protected static String formulaUpdate(String label, String expression, String resultType, long version) {
        return "{\"label\":\"" + label + "\",\"description\":\"\",\"required\":false,\"unique\":false,"
                + "\"settings\":{\"expression\":\"" + expression + "\",\"resultType\":\"" + resultType
                + "\"},\"version\":" + version + "}";
    }

    protected static long fieldVersion(TestBrowser admin, String object, String field) {
        Response one = admin.get(OBJECTS + "/" + object);
        List<Number> versions = JsonPath.read(one.body(), "$.data.fields[?(@.apiName=='" + field + "')].version");
        return versions.get(0).longValue();
    }

    // ---- rules ----

    protected static String ruleRequest(String name, String expression, String message) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"expression\":\""
                + expression + "\",\"errorMessage\":\"" + message + "\"}";
    }

    protected static String ruleRequest(String name, String expression, String message, String errorField,
            boolean active) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"expression\":\""
                + expression + "\",\"errorMessage\":\"" + message + "\",\"errorField\":"
                + (errorField == null ? "null" : "\"" + errorField + "\"") + ",\"active\":" + active + "}";
    }

    protected static String ruleUpdate(String name, String expression, String message, boolean active, long version) {
        return "{\"label\":\"" + name + "\",\"description\":\"\",\"expression\":\"" + expression
                + "\",\"errorMessage\":\"" + message + "\",\"errorField\":null,\"active\":" + active
                + ",\"version\":" + version + "}";
    }

    protected static Response createRule(TestBrowser admin, String name, String expression, String message) {
        return admin.postJson(RULES, ruleRequest(name, expression, message));
    }

    protected static void ruleExists(TestBrowser admin, String name, String expression, String message) {
        Response created = createRule(admin, name, expression, message);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    protected static List<String> ruleNames(TestBrowser admin) {
        Response list = admin.get(RULES);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[*].apiName");
    }

    // ---- trying ----

    protected static String values(String... namesAndValues) {
        StringBuilder out = new StringBuilder("{\"values\":{");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            out.append(i > 0 ? "," : "").append("\"").append(namesAndValues[i]).append("\":")
                    .append(namesAndValues[i + 1] == null ? "null" : "\"" + namesAndValues[i + 1] + "\"");
        }
        return out.append("}}").toString();
    }

    protected static String check(String expression, String purpose, String resultType) {
        return "{\"objectApiName\":\"Order__c\",\"expression\":\"" + expression + "\",\"purpose\":\"" + purpose
                + "\"" + (resultType == null ? "" : ",\"resultType\":\"" + resultType + "\"") + "}";
    }

    protected static String tryOut(String expression, String purpose, String valuesJson) {
        return "{\"objectApiName\":\"Order__c\",\"expression\":\"" + expression + "\",\"purpose\":\"" + purpose
                + "\"," + valuesJson.substring(1);
    }

    /** The first value a path with a filter finds, for example the text of one field among the fields of an object. */
    protected static String firstOf(Response response, String path) {
        List<String> found = JsonPath.read(response.body(), path);
        assertThat(found).as(path + " in " + response.body()).isNotEmpty();
        return found.get(0);
    }

    /** The message of the first problem that a refused publication or change names. */
    protected static String firstProblem(Response refused) {
        return JsonPath.read(refused.body(), "$.error.message");
    }

    /** Every problem a refused change names, in the words of the error's details. */
    protected static List<String> allProblems(Response refused) {
        List<String> problems = JsonPath.read(refused.body(), "$.error.fields.dependencies");
        return problems;
    }
}
