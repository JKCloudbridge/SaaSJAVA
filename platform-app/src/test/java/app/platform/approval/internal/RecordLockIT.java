package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The lock while an approval is pending (ADR-0148): a record with a pending request is refused to every way of changing
 * it, with the one stable answer, and is free again the moment the request ends, however it ends. Reading, sharing and
 * everything about other records is untouched. A member who holds the override ability may change a locked record and
 * the override is audited and written into the history; nobody else may, and no workflow, bulk call or import is a
 * quieter door.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class RecordLockIT extends ApprovalSupport {

    private static final String RECORDS_B = "/api/v1/objects/ObjectB__c/records";
    private static final String LOCKED_WORDS = "This record is being approved and cannot be changed until the approval "
            + "ends.";

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person approver,
            Person other, String process) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        Person other = person(organization, admin);
        String process = processExists(admin, "Lock", null, step("Only", "ANY", user(approver.userId())));
        return new Setup(organization, admin, submitter, approver, other, process);
    }

    /** A record of the submitter, already submitted, and the request. */
    private record Locked(String record, String request) {
    }

    private Locked locked(Setup setup) {
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"before\"}");
        return new Locked(record, submitted(setup.submitter().browser(), record, setup.process()));
    }

    private static byte[] zip(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        bytes[0] = 'P';
        bytes[1] = 'K';
        return bytes;
    }

    private static BytesResponse upload(TestBrowser browser, String record, String name) {
        return browser.bytes("POST", RECORDS + "/" + record + "/files", zip(100), "Content-Type", "application/zip",
                "X-File-Name", java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
    }

    // ---- what is refused ----

    @Test
    void everyMemberWhoMayEditIsRefusedWithTheOneStableAnswerAndNothingIsNamed() {
        Setup setup = setup();
        Locked locked = locked(setup);

        for (Person person : List.of(setup.submitter(), setup.other(), setup.approver())) {
            Response refused = change(person.browser(), locked.record(), "{\"fieldA__c\":\"after\"}");

            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(code(refused)).isEqualTo("RECORD_LOCKED");
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).isEqualTo(LOCKED_WORDS);
            assertThat(refused.body()).doesNotContain("Lock").doesNotContain(setup.approver().userId())
                    .doesNotContain(locked.request());
        }
        assertThat(fieldOf(setup.admin(), locked.record(), "fieldA__c")).isEqualTo("before");
    }

    @Test
    void deletingALockedRecordIsRefused() {
        Setup setup = setup();
        Locked locked = locked(setup);

        Response refused = setup.submitter().browser().request("DELETE", RECORDS + "/" + locked.record(), null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("RECORD_LOCKED");
        assertThat(setup.submitter().browser().get(RECORDS + "/" + locked.record()).status()).isEqualTo(200);
    }

    @Test
    void aBulkCallRefusesTheLockedRecordAndGoesOnWithTheOthers() {
        Setup setup = setup();
        Locked locked = locked(setup);
        String free = record(setup.submitter().browser(), "{\"fieldA__c\":\"free\"}");

        Response answer = setup.submitter().browser().postJson(RECORDS + "/bulk", "{\"operation\":\"UPDATE\","
                + "\"allOrNone\":false,\"records\":[{\"id\":\"" + locked.record() + "\",\"version\":"
                + recordVersion(setup.admin(), locked.record()) + ",\"fields\":{\"fieldA__c\":\"x\"}},"
                + "{\"id\":\"" + free + "\",\"version\":" + recordVersion(setup.admin(), free)
                + ",\"fields\":{\"fieldA__c\":\"y\"}}]}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.results[*].status"))
                .containsExactly("FAILED", "UPDATED");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.results[0].code")).isEqualTo("RECORD_LOCKED");
        assertThat(fieldOf(setup.admin(), locked.record(), "fieldA__c")).isEqualTo("before");
        assertThat(fieldOf(setup.admin(), free, "fieldA__c")).isEqualTo("y");
    }

    @Test
    void anAllOrNothingBulkCallThatTouchesALockedRecordChangesNothing() {
        Setup setup = setup();
        Locked locked = locked(setup);
        String free = record(setup.submitter().browser(), "{\"fieldA__c\":\"free\"}");

        Response answer = setup.submitter().browser().postJson(RECORDS + "/bulk", "{\"operation\":\"DELETE\","
                + "\"allOrNone\":true,\"records\":[{\"id\":\"" + free + "\"},{\"id\":\"" + locked.record() + "\"}]}");

        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.committed")).isFalse();
        assertThat(setup.submitter().browser().get(RECORDS + "/" + free).status()).as("rolled back").isEqualTo(200);
        assertThat(setup.submitter().browser().get(RECORDS + "/" + locked.record()).status()).isEqualTo(200);
    }

    @Test
    void aLockedRecordCanStillBeSharedWithARoleAndTheShareCanBeTakenBack() {
        Setup setup = setup();
        Locked locked = locked(setup);
        Response created = setup.admin().postJson("/api/v1/roles", "{\"name\":\"role-a\",\"description\":\"\"}");
        String role = JsonPath.read(created.body(), "$.data.id");
        String shares = RECORDS + "/" + locked.record() + "/shares";

        Response alone = setup.submitter().browser().request("PUT", shares, "{\"granteeType\":\"ROLE\","
                + "\"granteeId\":\"" + role + "\",\"accessLevel\":\"READ\"}");
        Response below = setup.submitter().browser().request("PUT", shares, "{\"granteeType\":"
                + "\"ROLE_AND_SUBORDINATES\",\"granteeId\":\"" + role + "\",\"accessLevel\":\"READ\"}");
        Response taken = setup.submitter().browser().request("DELETE", shares + "/" + role, null);

        assertThat(alone.status()).as("who may see it is not what is approved: " + alone.body()).isEqualTo(200);
        assertThat(below.status()).as(below.body()).isEqualTo(200);
        assertThat(taken.status()).isEqualTo(204);
    }

    @Test
    void theOwnerOfALockedRecordCannotBeChangedButItCanStillBeShared() {
        Setup setup = setup();
        Locked locked = locked(setup);

        Response owner = setup.submitter().browser().postJson(RECORDS + "/" + locked.record() + "/owner",
                "{\"ownerId\":\"" + setup.other().userId() + "\",\"version\":" + recordVersion(setup.admin(),
                        locked.record()) + "}");
        Response shared = setup.submitter().browser().request("PUT", RECORDS + "/" + locked.record() + "/shares",
                "{\"granteeType\":\"USER\",\"granteeId\":\"" + setup.other().userId()
                        + "\",\"accessLevel\":\"READ\"}");

        assertThat(owner.status()).as(owner.body()).isEqualTo(409);
        assertThat(code(owner)).isEqualTo("RECORD_LOCKED");
        assertThat(shared.status()).as("a share changes who may see it, not the record").isEqualTo(200);
    }

    @Test
    void aFileCannotBeAttachedToALockedRecordButItIsReadableAndFreeAgainAfterwards() {
        Setup setup = setup();
        Locked locked = locked(setup);

        BytesResponse refused = upload(setup.submitter().browser(), locked.record(), "contract.zip");

        assertThat(refused.status()).as(refused.text()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.text(), "$.error.code")).isEqualTo("RECORD_LOCKED");
        assertThat(approve(setup.approver().browser(), locked.request()).status()).isEqualTo(200);
        assertThat(upload(setup.submitter().browser(), locked.record(), "contract.zip").status()).isEqualTo(201);
    }

    @Test
    void readingASearchAListAndTheApprovalPanelAreUntouchedByTheLock() {
        Setup setup = setup();
        Locked locked = locked(setup);

        assertThat(setup.other().browser().get(RECORDS + "/" + locked.record()).status()).isEqualTo(200);
        assertThat(setup.other().browser().get(RECORDS + "?limit=10").status()).isEqualTo(200);
        Response panel = setup.submitter().browser().get(RECORDS + "/" + locked.record() + "/approval");
        assertThat(panel.status()).as(panel.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(panel.body(), "$.data.locked")).isTrue();
    }

    @Test
    void otherRecordsAndOtherObjectsAreFreeWhileOneRecordIsLocked() {
        Setup setup = setup();
        Locked locked = locked(setup);
        String free = record(setup.submitter().browser(), "{\"fieldA__c\":\"free\"}");

        assertThat(change(setup.submitter().browser(), free, "{\"fieldA__c\":\"changed\"}").status()).isEqualTo(200);
        Response made = setup.admin().postJson(RECORDS_B, "{\"fields\":{\"fieldA__c\":\"b\"}}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        assertThat(locked.request()).isNotBlank();
    }

    // ---- the lock opens however the request ends ----

    @Test
    void theLockOpensWhenTheRequestIsApprovedRejectedRecalledOrCancelled() {
        Setup setup = setup();
        for (String ending : List.of("approve", "reject", "recall", "cancel")) {
            Locked locked = locked(setup);
            assertThat(change(setup.submitter().browser(), locked.record(), "{\"fieldA__c\":\"x\"}").status())
                    .as("locked before " + ending).isEqualTo(409);

            TestBrowser who = switch (ending) {
                case "approve", "reject" -> setup.approver().browser();
                case "recall" -> setup.submitter().browser();
                default -> setup.admin();
            };
            Response ended = decide(who, locked.request(), ending, "because");
            assertThat(ended.status()).as(ending + ": " + ended.body()).isEqualTo(200);

            assertThat(change(setup.submitter().browser(), locked.record(), "{\"fieldA__c\":\"" + ending + "\"}")
                    .status()).as("free after " + ending).isEqualTo(200);
        }
    }

    // ---- the override ----

    @Test
    void aMemberWithTheOverrideAbilityChangesALockedRecordAndItIsAuditedAndWrittenIntoTheHistory() {
        Setup setup = setup();
        Person steward = person(setup.organization(), setup.admin(), "approvals.override-lock");
        Locked locked = locked(setup);

        Response changed = change(steward.browser(), locked.record(), "{\"fieldA__c\":\"corrected\"}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(fieldOf(setup.admin(), locked.record(), "fieldA__c")).isEqualTo("corrected");
        assertThat(statusOf(setup.admin(), locked.request())).as("the request goes on").isEqualTo("PENDING");
        assertThat(historyOf(setup.admin(), locked.request())).contains("LOCK_OVERRIDDEN");
        assertThat(audited(setup.organization(), "approval.lock.overridden")).isEqualTo(1);
        assertThat(change(setup.submitter().browser(), locked.record(), "{\"fieldA__c\":\"again\"}").status())
                .as("the lock stays for everyone else").isEqualTo(409);
    }

    @Test
    void aMemberWithoutTheOverrideAbilityLeavesNoOverrideBehind() {
        Setup setup = setup();
        Locked locked = locked(setup);

        change(setup.other().browser(), locked.record(), "{\"fieldA__c\":\"x\"}");

        assertThat(historyOf(setup.admin(), locked.request())).doesNotContain("LOCK_OVERRIDDEN");
        assertThat(audited(setup.organization(), "approval.lock.overridden")).isZero();
    }

    // ---- links into a locked record ----

    @Test
    void removingARecordWhosePointerWouldHaveToBeClearedInALockedRecordIsRefusedForAMemberWithoutTheOverride() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Response lookup = admin.postJson(METADATA_OBJECTS + "/" + OBJECT + "/fields",
                "{\"name\":\"parent\",\"label\":\"parent\",\"type\":\"LOOKUP\",\"settings\":{\"targetObject\":"
                        + "\"ObjectB__c\"}}");
        assertThat(lookup.status()).as(lookup.body()).isEqualTo(201);
        Person approver = person(organization, admin);
        Person submitter = personAlso(organization, admin,
                "{\"key\":\"ObjectB__c\",\"actions\":[\"read\",\"create\",\"delete\"]}",
                "{\"key\":\"ObjectB__c.fieldA__c\",\"actions\":[\"edit\"]},"
                        + "{\"key\":\"ObjectA__c.parent__c\",\"actions\":[\"edit\"]}");
        String process = processExists(admin, "Link", null, step("Only", "ANY", user(approver.userId())));
        Response target = submitter.browser().postJson(RECORDS_B, "{\"fields\":{\"fieldA__c\":\"target\"}}");
        assertThat(target.status()).as(target.body()).isEqualTo(201);
        String targetId = JsonPath.read(target.body(), "$.data.id");
        String record = record(submitter.browser(), "{\"fieldA__c\":\"holder\",\"parent__c\":\"" + targetId + "\"}");
        submitted(submitter.browser(), record, process);

        Response refused = submitter.browser().request("DELETE", RECORDS_B + "/" + targetId, null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("RECORD_LOCKED");
        assertThat(submitter.browser().get(RECORDS_B + "/" + targetId).status()).as("nothing was removed")
                .isEqualTo(200);
        assertThat(fieldOf(admin, record, "parent__c")).isEqualTo(targetId);
    }

    // ---- the lock and the request are one fact ----

    @Test
    void thereIsNoLockWithoutAPendingRequestAndNoPendingRequestWithoutALockedRecord() {
        Setup setup = setup();
        Locked locked = locked(setup);

        assertThat(scalar(setup.organization(), "select count(*) from approval_instance where status = 'PENDING' "
                + "and record_id = cast(? as uuid)", locked.record())).isEqualTo(1);
        assertThat(approve(setup.approver().browser(), locked.request()).status()).isEqualTo(200);
        assertThat(scalar(setup.organization(), "select count(*) from approval_instance where status = 'PENDING' "
                + "and record_id = cast(? as uuid)", locked.record())).isZero();
        assertThat(change(setup.submitter().browser(), locked.record(), "{\"fieldA__c\":\"free\"}").status())
                .isEqualTo(200);
    }
}
