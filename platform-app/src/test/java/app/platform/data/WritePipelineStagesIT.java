package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The write pipeline end to end (Sprint 15, ADR-0091), through the real API, on a {@code Ticket__c} object that has a
 * required text, a number with a default, a picklist with a default, a second number, two formulas that chain on each
 * other and a third that can fail, an auto-number and a validation rule. What the tests hold:
 *
 * <ul>
 * <li>the stages run in the documented order, and the first stage with a problem answers alone with all of its problems
 * and saves nothing;</li>
 * <li>defaults, empty text, required fields on create and on update (judged on the record after the change);</li>
 * <li>validation rules that reject, pass, read a formula, and cannot be evaluated;</li>
 * <li>formulas calculated in order, stored, recalculated on every write, and a record that passes reads back
 * exactly as written.</li>
 * </ul>
 */
@PlatformIntegrationTest
class WritePipelineStagesIT extends PipelineSupport {

    private static final String TICKETS = "/api/v1/objects/Ticket__c/records";

    private TestBrowser tickets(Organization organization) {
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Ticket", "Tickets");
        String levels = ",\"settings\":{\"values\":[{\"value\":\"low\",\"label\":\"low\",\"active\":true},"
                + "{\"value\":\"high\",\"label\":\"high\",\"active\":true}]}";
        field(admin, "title", "TEXT", ",\"required\":true,\"settings\":{\"maxLength\":20}");
        field(admin, "qty", "NUMBER", ",\"defaultValue\":\"5\",\"settings\":{\"digits\":3}");
        field(admin, "level", "PICKLIST", ",\"defaultValue\":\"low\"" + levels);
        field(admin, "den", "NUMBER", "");
        field(admin, "twice", "FORMULA", ",\"settings\":{\"expression\":\"qty__c * 2\",\"resultType\":\"NUMBER\"}");
        field(admin, "fourfold", "FORMULA",
                ",\"settings\":{\"expression\":\"twice__c * 2\",\"resultType\":\"NUMBER\"}");
        field(admin, "ratio", "FORMULA",
                ",\"settings\":{\"expression\":\"qty__c / den__c\",\"resultType\":\"DECIMAL\"}");
        field(admin, "code", "AUTO_NUMBER", ",\"settings\":{\"prefix\":\"T-\",\"startAt\":100,\"width\":3}");
        rule(admin, "Bad", "title__c = 'bad'", "Titles must not be bad.", "title__c");
        return admin;
    }

    private static void field(TestBrowser admin, String name, String type, String extra) {
        Response created = admin.postJson(METADATA_OBJECTS + "/Ticket__c/fields", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"type\":\"" + type + "\"" + extra + "}");
        assertThat(created.status()).as(name + ": " + created.body()).isEqualTo(201);
    }

    private static void rule(TestBrowser admin, String name, String expression, String message, String errorField) {
        Response created = admin.postJson(METADATA_OBJECTS + "/Ticket__c/validation-rules", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"expression\":\"" + expression + "\",\"errorMessage\":\""
                + message + "\"" + (errorField == null ? "" : ",\"errorField\":\"" + errorField + "\"") + "}");
        assertThat(created.status()).as(name + ": " + created.body()).isEqualTo(201);
    }

    private static Response make(TestBrowser browser, String fieldsJson) {
        return createIn(browser, TICKETS, fieldsJson);
    }

    private static Response change(TestBrowser browser, String id, String fieldsJson) {
        return patchIn(browser, TICKETS, id, currentVersion(browser, TICKETS, id), fieldsJson);
    }

    private static Map<String, Object> fields(Response response) {
        return fieldsOf(response);
    }

    private static BigDecimal number(Map<String, Object> fields, String key) {
        return new BigDecimal(fields.get(key).toString());
    }

    // ---- defaults, auto-number, formulas ----

    @Test
    void aNewRecordGetsItsDefaultsItsNumberAndItsFormulasAndReadsBackAsStored() {
        TestBrowser admin = tickets(organization());

        Response created = make(admin, "{\"title__c\":\"first\"}");
        Response read = admin.get(TICKETS + "/" + idOf(created));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Map<String, Object> fields = fields(read);
        assertThat(fields.get("title__c")).isEqualTo("first");
        assertThat(number(fields, "qty__c")).isEqualByComparingTo("5");
        assertThat(fields.get("level__c")).isEqualTo("low");
        assertThat(fields.get("code__c")).isEqualTo("T-100");
        assertThat(number(fields, "twice__c")).isEqualByComparingTo("10");
        assertThat(number(fields, "fourfold__c")).as("a formula that uses a formula").isEqualByComparingTo("20");
        assertThat(fields).as("no value in an input: the formula has no value").doesNotContainKey("ratio__c");
        assertThat(fields(created)).isEqualTo(fields);
    }

    @Test
    void aDefaultAppliesOnlyWhenTheKeyIsAbsent() {
        TestBrowser admin = tickets(organization());

        Map<String, Object> fields = fields(make(admin, "{\"title__c\":\"first\",\"qty__c\":7,\"level__c\":\"high\"}"));

        assertThat(number(fields, "qty__c")).isEqualByComparingTo("7");
        assertThat(fields.get("level__c")).isEqualTo("high");
        assertThat(number(fields, "twice__c")).isEqualByComparingTo("14");
    }

    @Test
    void theNumberCountsUpPerRecordIsNeverGivenOnUpdateAndIsNeverReused() {
        TestBrowser admin = tickets(organization());

        String first = idOf(make(admin, "{\"title__c\":\"a\"}"));
        String second = idOf(make(admin, "{\"title__c\":\"b\"}"));
        assertThat(change(admin, first, "{\"title__c\":\"changed\"}").status()).isEqualTo(200);
        admin.request("DELETE", TICKETS + "/" + second, null);
        Response third = make(admin, "{\"title__c\":\"c\"}");

        assertThat(fields(admin.get(TICKETS + "/" + first)).get("code__c")).isEqualTo("T-100");
        assertThat(fields(third).get("code__c")).as("the number of a deleted record is not given again")
                .isEqualTo("T-102");
    }

    @Test
    void formulasAreRecalculatedOnEveryWriteAndFollowTheirInputs() {
        TestBrowser admin = tickets(organization());
        String id = idOf(make(admin, "{\"title__c\":\"a\"}"));

        Response changed = change(admin, id, "{\"qty__c\":200,\"den__c\":4}");
        Response cleared = change(admin, id, "{\"den__c\":null}");

        Map<String, Object> fields = fields(changed);
        assertThat(number(fields, "twice__c")).isEqualByComparingTo("400");
        assertThat(number(fields, "fourfold__c")).isEqualByComparingTo("800");
        assertThat(number(fields, "ratio__c")).isEqualByComparingTo("50");
        assertThat(fields(cleared)).as("an input is gone: the result is gone").doesNotContainKey("ratio__c");
    }

    // ---- required, empty text, types on update ----

    @Test
    void aRequiredFieldMustHaveAValueAfterEveryWriteAndAnEmptyTextIsNoValue() {
        TestBrowser admin = tickets(organization());

        Response missing = make(admin, "{\"qty__c\":1}");
        Response empty = make(admin, "{\"title__c\":\"\"}");
        String id = idOf(make(admin, "{\"title__c\":\"a\"}"));
        Response cleared = admin.request("PATCH", TICKETS + "/" + id,
                "{\"version\":0,\"clear\":[\"title__c\"]}");
        Response emptied = change(admin, id, "{\"title__c\":\"\"}");

        for (Response refused : new Response[] {missing, empty, cleared, emptied}) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(problemAt(refused, "title__c")).isEqualTo("A value is required.");
        }
    }

    @Test
    void anUpdateIsJudgedOnTheRecordAfterTheChangeAndASavedRecordIsUntouchedWhenItIsRefused() {
        TestBrowser admin = tickets(organization());
        String id = idOf(make(admin, "{\"title__c\":\"a\"}"));
        long version = currentVersion(admin, TICKETS, id);

        Response tooBig = change(admin, id, "{\"qty__c\":1000}");
        Response bad = change(admin, id, "{\"title__c\":\"bad\"}");

        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(problemAt(tooBig, "qty__c")).contains("digits");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(currentVersion(admin, TICKETS, id)).as("nothing was saved").isEqualTo(version);
        assertThat(fields(admin.get(TICKETS + "/" + id)).get("title__c")).isEqualTo("a");
    }

    // ---- validation rules ----

    @Test
    void aRuleRejectsWithItsMessageAtItsFieldAndAGoodRecordPasses() {
        TestBrowser admin = tickets(organization());

        Response rejected = make(admin, "{\"title__c\":\"bad\"}");
        Response passed = make(admin, "{\"title__c\":\"good\"}");

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(problemAt(rejected, "title__c")).isEqualTo("Titles must not be bad.");
        assertThat(passed.status()).as(passed.body()).isEqualTo(201);
    }

    @Test
    void aRuleThatReadsAFormulaJudgesTheValueThisSaveGivesIt() {
        TestBrowser admin = tickets(organization());
        rule(admin, "Big", "twice__c > 1000", "Too big.", "qty__c");
        String id = idOf(make(admin, "{\"title__c\":\"a\",\"qty__c\":400}"));

        Response create = make(admin, "{\"title__c\":\"a\",\"qty__c\":600}");
        Response update = change(admin, id, "{\"qty__c\":600}");

        assertThat(create.status()).as("the rule sees 1200, not nothing").isEqualTo(400);
        assertThat(problemAt(create, "qty__c")).isEqualTo("Too big.");
        assertThat(update.status()).as("the rule sees 1200, not the stored 800").isEqualTo(400);
        assertThat(change(admin, id, "{\"qty__c\":450}").status()).as("900 passes").isEqualTo(200);
    }

    @Test
    void aRuleThatCannotBeEvaluatedRefusesTheSaveNamesTheRuleAndNeverItsText() {
        TestBrowser admin = tickets(organization());
        rule(admin, "Ratio", "qty__c / den__c > 100", "Ratio too high.", null);

        Response refused = make(admin, "{\"title__c\":\"a\",\"den__c\":0}");
        Response fine = make(admin, "{\"title__c\":\"a\",\"den__c\":1}");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(problemAt(refused, "fields")).contains("Ratio__c").contains("not saved");
        assertThat(refused.body()).doesNotContain("qty__c / den__c").doesNotContain("Ratio too high.");
        assertThat(fine.status()).as(fine.body()).isEqualTo(201);
    }

    // ---- formulas that cannot be calculated, and the order of the stages ----

    @Test
    void aFormulaThatCannotBeCalculatedRefusesTheSaveAndNamesTheFormulaField() {
        TestBrowser admin = tickets(organization());

        Response refused = make(admin, "{\"title__c\":\"a\",\"den__c\":0}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(placesOf(refused)).containsExactly("ratio__c");
        assertThat(problemAt(refused, "ratio__c")).contains("could not be calculated");
    }

    @Test
    void theStagesAnswerInTheDocumentedOrderAndEachAnswersAlone() {
        Organization organization = organization();
        TestBrowser admin = tickets(organization);
        TestBrowser reader = as(organization, holderWith(organization, admin, "Ticket__c",
                "[{\"key\":\"Ticket__c.title__c\",\"actions\":[\"read\"]},{\"key\":\"Ticket__c.qty__c\","
                        + "\"actions\":[\"read\"]}]", "read", "create", "update").member());

        // 1 shape before everything else
        Response shape = make(admin, "{\"title__c\":{\"deep\":1},\"nope__c\":1,\"qty__c\":1000}");
        assertThat(placesOf(shape)).as("shape").containsExactly("title__c");
        // 3 field permission before type
        Response permission = make(reader, "{\"qty__c\":1000,\"title__c\":\"x\"}");
        assertThat(placesOf(permission)).as("field permission").containsExactlyInAnyOrder("qty__c", "title__c");
        assertThat(problemAt(permission, "qty__c")).isEqualTo("You may not change this field.");
        // 4 type before required
        Response type = make(admin, "{\"qty__c\":1000}");
        assertThat(placesOf(type)).as("type").containsExactly("qty__c");
        // 5 required before rules (the rule would reject the title, but there is none)
        Response required = make(admin, "{\"qty__c\":1}");
        assertThat(placesOf(required)).as("required").containsExactly("title__c");
        // 7 rules before formulas (the formula cannot be calculated either)
        Response rules = make(admin, "{\"title__c\":\"bad\",\"den__c\":0}");
        assertThat(placesOf(rules)).as("rules").containsExactly("title__c");
        // 8 formulas last
        Response formulas = make(admin, "{\"title__c\":\"ok\",\"den__c\":0}");
        assertThat(placesOf(formulas)).as("formulas").containsExactly("ratio__c");
    }

    @Test
    void aStageReportsEveryProblemItFindsAtOnce() {
        TestBrowser admin = tickets(organization());

        Response refused = make(admin,
                "{\"title__c\":\"" + "x".repeat(21) + "\",\"qty__c\":1000,\"level__c\":\"nope\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(placesOf(refused)).containsExactlyInAnyOrder("title__c", "qty__c", "level__c");
    }
}
