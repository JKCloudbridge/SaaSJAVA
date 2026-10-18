package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Before-save automation (ADR-0160), through the real write path: the fields an automation sets are stored by the very
 * save that triggered it (one version, no second write), a condition decides, the later name wins a field two set,
 * what is set is judged afterwards by the rules of the pipeline like anything a person typed, a switched-off
 * automation does nothing, and the audit names fields and never values.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class BeforeSaveIT extends ActionsSupport {

    private static String setFields(String name, String... assignments) {
        return "{\"type\":\"SET_FIELDS\",\"name\":\"" + name + "\",\"settings\":{},\"assignments\":["
                + String.join(",", assignments) + "]}";
    }

    private record Setup(Organization organization, TestBrowser admin) {
    }

    private Setup setup() {
        Organization organization = organization();
        return new Setup(organization, adminWithData(organization));
    }

    @Test
    void anAutomationBeforeCreateSetsAFieldInTheSameSaveAndTheRecordHasVersionOne() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Stamp", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "stamped"), copy("fieldC__c", "fieldA__c")));

        String id = record(setup.admin(), "{\"fieldA__c\":\"hello\"}");

        assertThat(fieldOf(setup.admin(), id, "fieldB__c")).isEqualTo("stamped");
        assertThat(fieldOf(setup.admin(), id, "fieldC__c")).isEqualTo("hello");
        assertThat(versionOfRecord(setup.admin(), id)).as("one save, one version").isEqualTo(0L);
        settle(setup.organization());
        assertThat(executions(setup.organization())).as("nothing is queued: it ran inside the save").isEmpty();
    }

    @Test
    void theConditionDecidesAndItIsJudgedOnTheRecordAsItIsSaved() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Vip", recordTrigger("BEFORE_CREATE"), "fieldA__c = 'vip'",
                setFields("Set", value("fieldB__c", "priority")));

        String vip = record(setup.admin(), "{\"fieldA__c\":\"vip\"}");
        String other = record(setup.admin(), "{\"fieldA__c\":\"other\"}");

        assertThat(fieldOf(setup.admin(), vip, "fieldB__c")).isEqualTo("priority");
        assertThat(setup.admin().get(RECORDS + "/" + other).body()).doesNotContain("priority");
    }

    @Test
    void anAutomationBeforeUpdateRunsOnAChangeAndNotOnACreate() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Touch", recordTrigger("BEFORE_UPDATE"), null,
                setFields("Set", value("fieldB__c", "touched")));

        String id = record(setup.admin(), "{\"fieldA__c\":\"one\"}");
        assertThat(setup.admin().get(RECORDS + "/" + id).body()).as("not on create").doesNotContain("touched");

        Response changed = changeRecord(setup.admin(), id, "{\"fieldA__c\":\"two\"}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(fieldOf(setup.admin(), id, "fieldB__c")).isEqualTo("touched");
        assertThat(versionOfRecord(setup.admin(), id)).isEqualTo(1L);
    }

    @Test
    void whenTwoSetTheSameFieldTheLaterNameWinsWhateverTheOrderTheyWereMade() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Zeta", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "from-zeta")));
        workflowExists(setup.admin(), "Alpha", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "from-alpha")));

        String id = record(setup.admin(), "{\"fieldA__c\":\"x\"}");

        assertThat(fieldOf(setup.admin(), id, "fieldB__c")).isEqualTo("from-zeta");
    }

    @Test
    void whatAnAutomationSetsIsJudgedByTheValidationRulesLikeAnythingElse() {
        Setup setup = setup();
        addRule(setup.admin(), "NoBlocked", "fieldB__c = 'blocked'", "That status is not allowed.");
        workflowExists(setup.admin(), "Block", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "blocked")));

        Response refused = setup.admin().postJson(RECORDS, "{\"fields\":{\"fieldA__c\":\"x\"}}");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("That status is not allowed.");
        assertThat(JsonPath.<List<String>>read(setup.admin().get(RECORDS).body(), "$.data[*].id")).isEmpty();
    }

    @Test
    void aSwitchedOffAutomationDoesNothing() {
        Setup setup = setup();
        String name = workflowExists(setup.admin(), "Stamp", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "stamped")));
        assertThat(switchTo(setup.admin(), name, false).status()).isEqualTo(200);

        String id = record(setup.admin(), "{\"fieldA__c\":\"x\"}");

        assertThat(setup.admin().get(RECORDS + "/" + id).body()).doesNotContain("stamped");
    }

    @Test
    void theBulkPathRunsTheAutomationsToo() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Stamp", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "stamped")));

        Response bulk = setup.admin().postJson(RECORDS + "/bulk",
                "{\"operation\":\"CREATE\",\"allOrNone\":true,\"records\":["
                        + "{\"fields\":{\"fieldA__c\":\"one\"}},{\"fields\":{\"fieldA__c\":\"two\"}}]}");

        assertThat(bulk.status()).as(bulk.body()).isIn(200, 201);
        assertThat(JsonPath.<List<String>>read(setup.admin().get(RECORDS).body(), "$.data[*].fields.fieldB__c"))
                .containsExactly("stamped", "stamped");
    }

    @Test
    void theAuditNamesTheFieldsAnAutomationChangedAndNeverTheValues() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Stamp", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "SECRET-STAMP")));

        String id = record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());

        List<String> audits = owner("select to_jsonb(a)::text from audit_record a where a.record_id = ?", id);
        List<String> events = owner("select to_jsonb(o)::text from outbox_event o where o.payload ->> 'recordId' = ?",
                id);
        assertThat(String.join("", audits)).contains("fieldB__c").doesNotContain("SECRET-STAMP");
        assertThat(String.join("", events)).contains("fieldB__c").doesNotContain("SECRET-STAMP");
    }

    @Test
    void theAutomationsOfOneOrganizationNeverRunInAnother() {
        Setup mine = setup();
        Setup theirs = setup();
        workflowExists(mine.admin(), "Stamp", recordTrigger("BEFORE_CREATE"), null,
                setFields("Set", value("fieldB__c", "stamped")));

        String id = record(theirs.admin(), "{\"fieldA__c\":\"x\"}");

        assertThat(theirs.admin().get(RECORDS + "/" + id).body()).doesNotContain("stamped");
    }
}
