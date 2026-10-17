package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The actions that change the world (ADR-0138, ADR-0139): an update and a create run through the whole write pipeline
 * as the platform, change no owner, share or version on their own account, announce what they did with the chain, and
 * are audited by name; every refusal of the data is a stable reason that is not retried and leaves no value anywhere;
 * a mail is queued once, to the person the record names, with facts only.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowActionsIT extends ActionsSupport {

    private static final String OBJECT_B = "ObjectB__c";
    private static final String RECORDS_B = "/api/v1/objects/" + OBJECT_B + "/records";

    private record Setup(Organization organization, TestBrowser admin) {
    }

    private Setup setup() {
        Organization organization = organization();
        return new Setup(organization, adminWithData(organization));
    }

    // ---- UPDATE_RECORD ----

    @Test
    void anUpdateSetsAWrittenValueAndACopyThroughTheWholePipelineAndBumpsTheVersionOnce() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Mark", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("fieldB__c", "done"), copy("fieldC__c", "fieldA__c")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"hello\"}");
        settle(setup.organization());
        long before = versionOfRecord(setup.admin(), id);

        runWorker();
        settle(setup.organization());

        assertThat(fieldOf(setup.admin(), id, "fieldB__c")).isEqualTo("done");
        assertThat(fieldOf(setup.admin(), id, "fieldC__c")).isEqualTo("hello");
        assertThat(versionOfRecord(setup.admin(), id)).as("one update, one version").isEqualTo(before + 1);
        String execution = theExecution(setup.organization());
        assertThat(execution(setup.organization(), execution)).containsEntry("status", "COMPLETED");
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:COMPLETED:1");
    }

    @Test
    void theUpdateIsAnnouncedWithTheChainAndAuditedWithTheWorkflowAndNoPerson() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Mark", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("fieldB__c", "done")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"hello\"}");
        settle(setup.organization());
        runWorker();
        settle(setup.organization());
        String execution = theExecution(setup.organization());

        List<String> events = owner("select payload::text from outbox_event where event_type = 'record.updated' "
                + "and payload ->> 'recordId' = ?", id);
        List<String> audits = owner("select attributes::text || '|' || coalesce(actor_user_id::text, 'none') "
                + "from audit_record where event_type = 'data.record.updated' and record_id = ?", id);

        assertThat(events).hasSize(1);
        assertThat(JsonPath.<Integer>read(events.get(0), "$.chain.depth")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(events.get(0), "$.chain.workflows")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(events.get(0), "$.fields")).containsExactly("fieldB__c");
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit).contains("\"workflow\": \"Mark__c\"").contains(execution).contains("platform");
            assertThat(audit).endsWith("|none");
            assertThat(audit).doesNotContain("done");
        });
    }

    @Test
    void anUpdateChangesNoOwnerAndMakesNoShare() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Mark", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("fieldB__c", "done")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"hello\"}");
        settle(setup.organization());
        String ownerBefore = JsonPath.read(setup.admin().get(RECORDS + "/" + id).body(), "$.data.ownerId");

        runWorker();

        assertThat((String) JsonPath.read(setup.admin().get(RECORDS + "/" + id).body(), "$.data.ownerId"))
                .isEqualTo(ownerBefore);
        assertThat(scalar(setup.organization(), "select count(*) from record_share")).isZero();
    }

    @Test
    void anUpdateThatWouldChangeNothingIsNotAChangeNoVersionNoEventNoAudit() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Mark", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("fieldB__c", "same")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"hello\",\"fieldB__c\":\"same\"}");
        settle(setup.organization());
        long before = versionOfRecord(setup.admin(), id);

        runWorker();
        settle(setup.organization());

        assertThat(versionOfRecord(setup.admin(), id)).isEqualTo(before);
        assertThat(owner("select count(*) from outbox_event where event_type = 'record.updated' and payload ->> "
                + "'recordId' = ?", id)).containsExactly("0");
        assertThat(statuses(setup.organization())).containsExactly("COMPLETED");
    }

    @Test
    void aCopyOfAFieldWithNoValueEmptiesTheTarget() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Clear", recordTrigger("RECORD_CREATED"), null,
                update("Set", copy("fieldB__c", "fieldC__c")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"hello\",\"fieldB__c\":\"keep\"}");
        settle(setup.organization());

        runWorker();

        assertThat(setup.admin().get(RECORDS + "/" + id).body()).doesNotContain("keep");
        assertThat(statuses(setup.organization())).containsExactly("COMPLETED");
    }

    // ---- the refusals of the data ----

    @Test
    void aValidationRuleThatRejectsEndsTheExecutionAsADeadLetterWithAStableReasonAndNoRetry() {
        Setup setup = setup();
        addRule(setup.admin(), "NoLoud", "fieldB__c = 'LOUD-SECRET-TEXT'", "Never shout LOUD-SECRET-TEXT here.");
        workflowExists(setup.admin(), "Mark", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("fieldB__c", "LOUD-SECRET-TEXT")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"hello\"}");
        settle(setup.organization());

        runWorker();

        String execution = theExecution(setup.organization());
        assertThat(execution(setup.organization(), execution)).containsEntry("status", "DEAD_LETTERED")
                .containsEntry("reason", "rejected_by_rule").containsEntry("failure_type", "BackgroundWriteException");
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:FAILED:1");
        assertThat(setup.admin().get(RECORDS + "/" + id).body()).doesNotContain("LOUD-SECRET-TEXT");
        assertThat(rows(setup.organization(), "select reason, failure_type, last_reason, last_failure_type "
                + "from workflow_execution e join workflow_step s on s.execution_id = e.id").toString())
                .doesNotContain("LOUD").doesNotContain("shout");
    }

    @Test
    void aRequiredFieldThatWouldBeEmptiedIsARefusalAndNotARetry() {
        Setup setup = setup();
        constrain(setup.admin(), "fieldC__c", true, false);
        workflowExists(setup.admin(), "Wipe", recordTrigger("RECORD_CREATED"), null,
                update("Set", copy("fieldC__c", "fieldB__c")));
        record(setup.admin(), "{\"fieldA__c\":\"hello\",\"fieldC__c\":\"needed\"}");
        settle(setup.organization());

        runWorker();

        assertThat(execution(setup.organization(), theExecution(setup.organization())))
                .containsEntry("status", "DEAD_LETTERED").containsEntry("reason", "required_missing");
    }

    @Test
    void aValueAlreadyInUseOnAUniqueFieldIsAUniqueClash() {
        Setup setup = setup();
        constrain(setup.admin(), "fieldB__c", false, true);
        record(setup.admin(), "{\"fieldA__c\":\"first\",\"fieldB__c\":\"taken\"}");
        workflowExists(setup.admin(), "Take", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("fieldB__c", "taken")));
        record(setup.admin(), "{\"fieldA__c\":\"second\"}");
        settle(setup.organization());

        runWorker();

        assertThat(executions(setup.organization()).stream().map(row -> row.get("status") + ":" + row.get("reason")))
                .contains("DEAD_LETTERED:unique_clash");
    }

    @Test
    void aValueOfTheWrongTypeIsAnInvalidValueAtRunTimeEvenIfTheDefinitionWasSavedBeforeTheFieldChanged() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Count", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("quantity__c", "12")));
        // The definition was good when it was saved; the stored literal is then made unreadable (a later release
        // changed what a number may be): the pipeline refuses it and says only that the value is invalid.
        run(setup.organization(), "update workflow_version set actions = replace(actions::text, '\"12\"', "
                + "'\"twelve\"')::jsonb");
        record(setup.admin(), "{\"fieldA__c\":\"hello\"}");
        settle(setup.organization());

        runWorker();

        assertThat(execution(setup.organization(), theExecution(setup.organization())))
                .containsEntry("status", "DEAD_LETTERED").containsEntry("reason", "invalid_value");
    }

    // ---- CREATE_RECORD ----

    @Test
    void aCreateMakesARecordOwnedByTheOwnerOfTheTriggeringRecordAndLinksIt() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT_B, "origin", "LOOKUP", ",\"settings\":{\"targetObject\":\"" + OBJECT + "\"}");
        workflowExists(setup.admin(), "Follow", recordTrigger("RECORD_CREATED"), null,
                create("Make", OBJECT_B, "origin__c", value("fieldA__c", "follow-up"), copy("fieldB__c", "fieldA__c")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"source\"}");
        settle(setup.organization());
        String owner = JsonPath.read(setup.admin().get(RECORDS + "/" + id).body(), "$.data.ownerId");

        runWorker();
        settle(setup.organization());

        Response made = setup.admin().get(RECORDS_B);
        assertThat(JsonPath.<List<String>>read(made.body(), "$.data[*].fields.fieldA__c")).containsExactly("follow-up");
        assertThat(JsonPath.<List<String>>read(made.body(), "$.data[*].fields.fieldB__c")).containsExactly("source");
        assertThat(JsonPath.<List<String>>read(made.body(), "$.data[*].fields.origin__c")).containsExactly(id);
        assertThat(JsonPath.<List<String>>read(made.body(), "$.data[*].ownerId")).containsExactly(owner);
        assertThat(statuses(setup.organization())).containsExactly("COMPLETED");
        assertThat(owner("select count(*) from outbox_event where event_type = 'record.created' and payload ->> "
                + "'object' = ?", OBJECT_B)).containsExactly("1");
    }

    @Test
    void aCreateThatBreaksARuleOfTheOtherObjectIsADeadLetterAndMakesNoRecord() {
        Setup setup = setup();
        Response rule = setup.admin().postJson(METADATA_OBJECTS + "/" + OBJECT_B + "/validation-rules",
                "{\"name\":\"NoBlank\",\"label\":\"NoBlank\",\"expression\":\"fieldA__c = 'bad'\","
                        + "\"errorMessage\":\"Not bad.\"}");
        assertThat(rule.status()).as(rule.body()).isEqualTo(201);
        workflowExists(setup.admin(), "Follow", recordTrigger("RECORD_CREATED"), null,
                create("Make", OBJECT_B, null, value("fieldA__c", "bad")));
        record(setup.admin(), "{\"fieldA__c\":\"source\"}");
        settle(setup.organization());

        runWorker();

        assertThat(execution(setup.organization(), theExecution(setup.organization())))
                .containsEntry("status", "DEAD_LETTERED").containsEntry("reason", "rejected_by_rule");
        assertThat(JsonPath.<List<String>>read(setup.admin().get(RECORDS_B).body(), "$.data[*].id")).isEmpty();
    }

    // ---- the definition and the fields it names ----

    @Test
    void aFieldThatAnActionNamesCannotBeRemovedAndTheRefusalNamesTheWorkflow() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Mark", recordTrigger("RECORD_CREATED"), null,
                update("Set", value("fieldB__c", "done")));

        Response removed = setup.admin().request("DELETE", METADATA_OBJECTS + "/" + OBJECT + "/fields/fieldB__c", null);

        assertThat(removed.status()).as(removed.body()).isEqualTo(409);
        assertThat(removed.body()).contains("Mark__c");
    }

    // ---- SEND_EMAIL ----

    @Test
    void aMailIsQueuedOnceToTheMemberTheRecordNamesWithFactsOnly() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT, "owner", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, mail("Notice", "owner__c"));
        String member = setup.organization().admin().person().user().id().toString();
        String address = setup.organization().admin().person().email();
        record(setup.admin(), "{\"fieldA__c\":\"Quarterly report SECRET-TITLE\",\"owner__c\":\"" + member + "\"}");
        settle(setup.organization());

        runWorker();
        runWorker();

        List<String> queued = queuedMails(address);
        assertThat(queued).hasSize(1);
        assertThat(JsonPath.<Map<String, String>>read(queued.get(0), "$")).containsOnlyKeys("organization_id",
                "object", "object_label", "record_id");
        assertThat(queued.get(0)).doesNotContain("SECRET-TITLE").doesNotContain("Quarterly");
        assertThat(statuses(setup.organization())).containsExactly("COMPLETED");
    }

    @Test
    void aMailStepClaimedTwiceStillQueuesOneMailBecauseTheCompletedStepIsNeverRepeated() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT, "owner", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, mail("Notice", "owner__c"),
                action("After", "after.event"));
        String member = setup.organization().admin().person().user().id().toString();
        String address = setup.organization().admin().person().email();
        record(setup.admin(), "{\"fieldA__c\":\"x\",\"owner__c\":\"" + member + "\"}");
        settle(setup.organization());
        runWorker();
        // The instance died before the end of the execution: it is claimed again, with the first step already done.
        run(setup.organization(), "update workflow_execution set status = 'RUNNING', finished_at = null, "
                + "reason = null, attempts = 1, locked_until = now() - interval '1 minute', version = version + 1");
        run(setup.organization(), "update workflow_step set status = 'COMPLETED' where position = 1");
        run(setup.organization(), "delete from workflow_step where position = 2");

        runWorker();

        assertThat(queuedMails(address)).as("the completed mail step is not run again").hasSize(1);
        assertThat(statuses(setup.organization())).containsExactly("COMPLETED");
    }

    @Test
    void aRecordThatNamesNobodyIsAStableReasonNoRetryCanMend() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT, "owner", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, mail("Notice", "owner__c"));
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());

        runWorker();

        assertThat(execution(setup.organization(), theExecution(setup.organization())))
                .containsEntry("status", "DEAD_LETTERED").containsEntry("reason", "recipient_missing");
    }

    @Test
    void aPersonWhoIsNotAMemberOfTheOrganizationIsNeverMailed() {
        Setup setup = setup();
        addField(setup.admin(), OBJECT, "owner", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        workflowExists(setup.admin(), "Tell", recordTrigger("RECORD_CREATED"), null, mail("Notice", "owner__c"));
        // A made-up identifier is refused when the record is written; a member who left is not mailed either.
        TestOrganizations.Member leaver = TestOrganizations.join(users, setup.organization().tenant(), false);
        String leaverId = leaver.person().user().id().toString();
        record(setup.admin(), "{\"fieldA__c\":\"x\",\"owner__c\":\"" + leaverId + "\"}");
        settle(setup.organization());
        run(setup.organization(), "update membership set status = 'DEACTIVATED', version = version + 1 "
                + "where user_id = cast(? as uuid)", leaverId);

        runWorker();

        assertThat(queuedMails(leaver.person().email())).isEmpty();
        assertThat(execution(setup.organization(), theExecution(setup.organization())))
                .containsEntry("status", "DEAD_LETTERED").containsEntry("reason", "recipient_not_member");
    }
}
