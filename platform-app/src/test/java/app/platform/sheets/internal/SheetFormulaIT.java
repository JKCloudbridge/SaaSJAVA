package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Formulas through the real application on a real PostgreSQL (SH2, ADR-0252): a formula is stored in its one canonical
 * form, read back, and calculated by the server; a calculated column is drawn for the row the server calculates; a
 * refusal is in fixed words that repeat nothing typed; and who may read the answers is the rule of reading the version,
 * for the person who may change the record, the person who may only read it, another organization and nobody.
 */
@PlatformIntegrationTest
class SheetFormulaIT extends SheetSupport {

    private static String calculation(String owner, int number) {
        return versions(owner) + "/" + number + "/calculation";
    }

    private static String named() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1",
                " sum( @{c1,r1} : @{c1,r2} ) ");
        return json(document);
    }

    @Test
    void aFormulaIsStoredInItsOneFormAndTheServerCalculatesIt() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response saved = save(setting.admin(), owner, 1, 0, named());

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        Response read = read(setting.admin(), owner, 1);
        assertThat(JsonPath.<String>read(read.body(), "$.data.grid.sheets[0].cells['r3:c1'].formula"))
                .isEqualTo("SUM(@{c1,r1}:@{c1,r2})");
        Response answers = setting.admin().get(calculation(owner, 1));
        assertThat(answers.status()).as(answers.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(answers.body(), "$.data.cells[*].cell")).containsExactly("r3:c1");
        assertThat(JsonPath.<String>read(answers.body(), "$.data.cells[0].answer.kind")).isEqualTo("number");
        assertThat(JsonPath.<String>read(answers.body(), "$.data.cells[0].answer.value")).isEqualTo("0");
    }

    @Test
    void theSameMeaningTypedAnotherWayHasTheSameHash() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Response first = save(setting.admin(), owner, 1, 0, named());
        long version = rowVersion(first);

        Response again = save(setting.admin(), owner, 1, version,
                json(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "SUM(@{c1,r1}:@{c1,r2})")));

        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(again.body(), "$.data.gridHash"))
                .isEqualTo(JsonPath.<String>read(first.body(), "$.data.gridHash"));
    }

    @Test
    void aCalculatedColumnIsCalculatedForOneEmptyRow() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 1);
        Map<String, Object> document = FormulaDocs.withColumn(FormulaDocs.repeating(), "c2",
                "IF(@{c1}=\"\",\"\",@{c1}*2)");

        Response saved = save(setting.admin(), owner, 1, 0, json(document));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        Response answers = setting.admin().get(calculation(owner, 1));
        assertThat(JsonPath.<Integer>read(answers.body(), "$.data.bodyRows")).isEqualTo(1);
        assertThat(JsonPath.<String>read(answers.body(), "$.data.columns[0].column")).isEqualTo("c2");
        assertThat(JsonPath.<String>read(answers.body(), "$.data.columns[0].rows[0].kind")).isEqualTo("text");
        assertThat(JsonPath.<String>read(answers.body(), "$.data.columns[0].rows[0].value")).isEmpty();
    }

    @Test
    void aVersionInTheEarlierFormatHasNoFormulasAndAnswersWithNone() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response answers = setting.admin().get(calculation(owner, 1));

        assertThat(answers.status()).as(answers.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(answers.body(), "$.data.cells")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(answers.body(), "$.data.columns")).isEmpty();
    }

    @Test
    void aRefusalIsInFixedWordsThatRepeatNothingTyped() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response refused = save(setting.admin(), owner, 1, 0, json(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1",
                "\"HOSTILEMARK\"&@{c1,r1}")));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("grid.sheets[0].cells.r3:c1.formula").doesNotContain("HOSTILEMARK");
    }

    @Test
    void aFormulaThatDependsOnItselfIsRefused() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response refused = save(setting.admin(), owner, 1, 0, json(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1",
                "@{c1}+1")));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("A formula depends on itself");
    }

    // ---- who may read the answers ----

    @Test
    void thePersonWhoMayChangeTheRecordAndThePersonWhoMayOnlyReadItReadTheAnswersByTheRuleOfReading() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        assertThat(save(setting.admin(), owner, 1, 0, named()).status()).isEqualTo(200);
        TestBrowser reader = as(setting.organization(), reader(setting, owner));

        assertThat(reader.get(calculation(owner, 1)).status()).as("a draft is not the reader's").isEqualTo(404);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        assertThat(reader.get(calculation(owner, 1)).status()).as("the version in use is").isEqualTo(200);
        assertThat(reader.get(calculation(owner, 9)).status()).isEqualTo(404);
    }

    @Test
    void anotherOrganizationAndNobodyAndAForgedHostReachNothing() {
        Setting mine = setting();
        String owner = template(mine.admin());
        firstVersion(mine.admin(), owner, 2);
        assertThat(save(mine.admin(), owner, 1, 0, named()).status()).isEqualTo(200);
        Setting theirs = setting();
        TestBrowser nobody = new TestBrowser(port, mine.organization().host());
        String[] forged = {"X-Tenant-Id", theirs.organization().id().value().toString(), "X-Forwarded-Host",
            theirs.organization().host()};

        assertThat(theirs.admin().get(calculation(owner, 1)).status()).isEqualTo(404);
        assertThat(nobody.get(calculation(owner, 1)).status()).isEqualTo(401);
        assertThat(theirs.admin().get(calculation(owner, 1), forged).status()).isEqualTo(404);
        assertThat(mine.admin().get(calculation(owner, 1), forged).status()).as("mine is still mine").isEqualTo(200);
    }

    @Test
    void theLimitsTheScreenReadsNameTheFunctionsAndTheBounds() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response list = setting.admin().get(versions(owner));

        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.formulas.maxChars")).isEqualTo(4000);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data.limits.formulas.functions[*].name"))
                .hasSize(40).contains("SUM", "IF", "TEXT").doesNotContain("INDIRECT", "STDEV");
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data.limits.formulas.textFormats")).hasSize(9);
    }
}
