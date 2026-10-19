package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The action that tells a member in the application (ADR-0161), run by the real worker: one notice for the owner or for
 * the person a field names, with facts only, once however often the step is claimed, none for a member who switched
 * these notices off (and the step still completes), and stable reasons for a recipient who cannot be told.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class NotifyActionIT extends ActionsSupport {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String PREFERENCES = "/api/v1/notification-preferences";

    private static String notifyOwner(String name) {
        return "{\"type\":\"NOTIFY\",\"name\":\"" + name + "\",\"settings\":{\"recipient\":\"OWNER\"}}";
    }

    private static String notifyField(String name, String field) {
        return "{\"type\":\"NOTIFY\",\"name\":\"" + name + "\",\"settings\":{\"recipient\":\"FIELD\","
                + "\"recipientField\":\"" + field + "\"}}";
    }

    private record Setup(Organization organization, TestBrowser admin) {
    }

    private Setup setup() {
        Organization organization = organization();
        return new Setup(organization, adminWithData(organization));
    }

    @Test
    void theOwnerOfTheRecordIsToldOnceWithFactsAndNeverAValue() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, notifyOwner("Notice"));
        String id = record(setup.admin(), "{\"fieldA__c\":\"Quarterly report SECRET-TITLE\"}");
        settle(setup.organization());

        runWorker();
        runWorker();

        Response list = setup.admin().get(NOTIFICATIONS);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].title")).hasSize(1);
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].href")).isEqualTo("/records/" + OBJECT + "/" + id);
        assertThat(list.body()).doesNotContain("SECRET-TITLE").doesNotContain("Quarterly");
        assertThat(rows(setup.organization(), "select facts::text as facts from notification").get(0).get("facts"))
                .contains("Tell__c").doesNotContain("SECRET-TITLE");
        assertThat(statuses(setup.organization())).containsExactly("COMPLETED");
    }

    @Test
    void aStepClaimedTwiceStillMakesOneNoticeBecauseTheKeyIsTheKeyOfTheStep() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, notifyOwner("Notice"),
                action("After", "after.event"));
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());
        runWorker();
        // The instance died before the end of the execution: it is claimed again and the first step runs once more.
        run(setup.organization(), "update workflow_execution set status = 'RUNNING', finished_at = null, "
                + "reason = null, attempts = 1, locked_until = now() - interval '1 minute', version = version + 1");
        run(setup.organization(), "delete from workflow_step");

        runWorker();

        assertThat(scalar(setup.organization(), "select count(*) from notification")).isEqualTo(1);
    }

    @Test
    void thePersonAFieldNamesIsToldAndNobodyElse() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT, "reviewer", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        Member reviewer = TestOrganizations.join(users, setup.organization().tenant(), false);
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null,
                notifyField("Notice", "reviewer__c"));
        record(setup.admin(), "{\"fieldA__c\":\"x\",\"reviewer__c\":\"" + reviewer.person().user().id() + "\"}");
        settle(setup.organization());

        runWorker();

        assertThat(JsonPath.<List<String>>read(as(setup.organization(), reviewer).get(NOTIFICATIONS).body(),
                "$.data[*].id")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(setup.admin().get(NOTIFICATIONS).body(), "$.data[*].id")).isEmpty();
    }

    @Test
    void aMemberWhoSwitchedTheseNoticesOffGetsNoneAndTheStepStillCompletes() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, notifyOwner("Notice"));
        Response off = setup.admin().request("PUT", PREFERENCES, "{\"choices\":[{\"category\":\"WORKFLOWS\","
                + "\"channel\":\"IN_APP\",\"enabled\":false}]}");
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());

        runWorker();

        assertThat(scalar(setup.organization(), "select count(*) from notification")).isZero();
        assertThat(statuses(setup.organization())).as("a preference is not a failure").containsExactly("COMPLETED");
    }

    @Test
    void aRecordThatNamesNobodyIsAStableReasonNoRetryCanMend() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT, "reviewer", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null,
                notifyField("Notice", "reviewer__c"));
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());

        runWorker();

        assertThat(execution(setup.organization(), theExecution(setup.organization())))
                .containsEntry("status", "DEAD_LETTERED").containsEntry("reason", "recipient_missing");
    }

    @Test
    void aMemberWhoLeftIsNeverToldAndTheReasonIsStable() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT, "reviewer", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null,
                notifyField("Notice", "reviewer__c"));
        Member leaver = TestOrganizations.join(users, setup.organization().tenant(), false);
        String leaverId = leaver.person().user().id().toString();
        record(setup.admin(), "{\"fieldA__c\":\"x\",\"reviewer__c\":\"" + leaverId + "\"}");
        settle(setup.organization());
        run(setup.organization(), "update membership set status = 'DEACTIVATED', version = version + 1 "
                + "where user_id = cast(? as uuid)", leaverId);

        runWorker();

        assertThat(scalar(setup.organization(), "select count(*) from notification")).isZero();
        assertThat(execution(setup.organization(), theExecution(setup.organization())))
                .containsEntry("status", "DEAD_LETTERED").containsEntry("reason", "recipient_not_member");
    }

    @Test
    void theNoticeOfOneOrganizationNeverReachesAnother() {
        Setup mine = setup();
        Setup theirs = setup();
        workflowExists(mine.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, notifyOwner("Notice"));
        record(mine.admin(), "{\"fieldA__c\":\"x\"}");
        settle(mine.organization());

        runWorker();

        assertThat(scalar(mine.organization(), "select count(*) from notification")).isEqualTo(1);
        assertThat(scalar(theirs.organization(), "select count(*) from notification")).isZero();
        assertThat(JsonPath.<List<String>>read(theirs.admin().get(NOTIFICATIONS).body(), "$.data[*].id")).isEmpty();
    }
}
