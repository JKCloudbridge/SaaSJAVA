package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.RecordShares;
import app.platform.data.RecordShares.Granted;
import app.platform.security.Grantee;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sharing a record from Java (FA4, ADR-0188): the read share a module makes for an act it has decided itself. It never
 * overwrites a share that exists, works on a record a pending approval locks, takes back only a share of the kind asked
 * for, refuses a grantee that is not of this organization or is the owner, and keeps within the most shares a record
 * may have; each change is audited by the kind of grantee and the level, never by a name.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false",
    "platform.data.sharing.max-shares-per-record=3"})
class RecordSharesIT extends TemplateSupport {

    @Autowired
    private RecordShares shares;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    private <T> T inOrganization(Setting setting, Supplier<T> work) {
        return contexts.call(TenantContext.of(setting.organization().id()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private Granted grant(Setting setting, String template, UUID group) {
        return inOrganization(setting, () -> shares.grantRead(OBJECT, UUID.fromString(template),
                new Grantee(Grantee.Kind.GROUP, group), setting.organization().admin().person().user().id()));
    }

    private boolean revoke(Setting setting, String template, Grantee grantee) {
        return inOrganization(setting, () -> shares.revoke(OBJECT, UUID.fromString(template), grantee,
                setting.organization().admin().person().user().id()));
    }

    @Test
    void aReadShareIsMadeOnceAndASecondCallChangesNothing() {
        Setting setting = setting();
        Person reader = reader(setting);
        UUID group = UUID.fromString(group(setting.admin(), "g-" + System.nanoTime(), reader));
        String template = template(author(setting).browser(), "WI", "PRD");

        assertThat(grant(setting, template, group)).isEqualTo(Granted.CREATED);
        assertThat(grant(setting, template, group)).isEqualTo(Granted.ALREADY_SHARED);

        assertThat(shares(setting.organization(), template)).isEqualTo(1);
        assertThat(scalar(setting.organization(), "select access_level from record_share where record_id = ?",
                UUID.fromString(template))).isEqualTo(1);
        assertThat(listed(reader.browser())).contains(template);
        assertThat(audited(setting.organization(), "data.record.shared")).isEqualTo(1);
    }

    @Test
    void aShareMadeByHandKeepsItsLevelAndIsReportedAsAlreadyThere() {
        Setting setting = setting();
        Person owner = author(setting);
        UUID group = UUID.fromString(group(setting.admin(), "g-" + System.nanoTime()));
        String template = template(owner.browser(), "WI", "PRD");
        Response byHand = owner.browser().request("PUT", RECORDS + "/" + template + "/shares",
                "{\"granteeType\":\"GROUP\",\"granteeId\":\"" + group + "\",\"accessLevel\":\"READ_WRITE\"}");
        assertThat(byHand.status()).as(byHand.body()).isEqualTo(200);

        assertThat(grant(setting, template, group)).isEqualTo(Granted.ALREADY_SHARED);

        assertThat(scalar(setting.organization(), "select access_level from record_share where record_id = ?",
                UUID.fromString(template))).as("not lowered to read").isEqualTo(2);
    }

    @Test
    void itWorksOnARecordThatAPendingApprovalLocksAndOnlyReadCanBeGivenFromHere() {
        Setting setting = setting();
        Person owner = author(setting);
        Person approver = approver(setting);
        UUID group = UUID.fromString(group(setting.admin(), "g-" + System.nanoTime()));
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        String template = templateWithSheet(owner.browser(), "WI", "PRD");
        submitted(owner.browser(), template, process);
        assertThat(owner.browser().request("PATCH", RECORDS + "/" + template,
                "{\"fields\":{\"name\":\"changed\"},\"version\":0}").status()).as("the record is locked")
                .isEqualTo(409);

        assertThat(grant(setting, template, group)).isEqualTo(Granted.CREATED);

        assertThat(shares(setting.organization(), template)).isEqualTo(1);
        assertThat(scalar(setting.organization(), "select access_level from record_share where record_id = ?",
                UUID.fromString(template))).isEqualTo(1);
    }

    @Test
    void aGranteeThatIsNotOfThisOrganizationOrIsTheOwnerAndARecordThatIsNotThereAreRefused() {
        Setting setting = setting();
        Setting other = setting();
        Person owner = author(setting);
        String template = template(owner.browser(), "WI", "PRD");
        UUID foreign = UUID.fromString(group(other.admin(), "theirs-" + System.nanoTime()));

        assertThatThrownBy(() -> grant(setting, template, foreign)).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> grant(setting, template, UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inOrganization(setting, () -> shares.grantRead(OBJECT, UUID.fromString(template),
                Grantee.user(UUID.fromString(owner.userId())), UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.fields()).isNotEmpty());
        assertThatThrownBy(() -> inOrganization(setting, () -> shares.grantRead(OBJECT, UUID.randomUUID(),
                new Grantee(Grantee.Kind.GROUP, UUID.randomUUID()), UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(shares(setting.organization(), template)).isZero();
    }

    @Test
    void aRecordHasAtMostTheConfiguredNumberOfSharesAndTheBoundIsAConflict() {
        Setting setting = setting();
        String template = template(author(setting).browser(), "WI", "PRD");
        for (int i = 0; i < 3; i++) {
            assertThat(grant(setting, template, UUID.fromString(group(setting.admin(), "g" + i + System.nanoTime()))))
                    .isEqualTo(Granted.CREATED);
        }

        UUID fourth = UUID.fromString(group(setting.admin(), "g4-" + System.nanoTime()));

        assertThatThrownBy(() -> grant(setting, template, fourth)).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(shares(setting.organization(), template)).isEqualTo(3);
    }

    @Test
    void revokingTakesBackOnlyAShareOfTheKindAskedForAndSaysWhenThereWasNone() {
        Setting setting = setting();
        UUID group = UUID.fromString(group(setting.admin(), "g-" + System.nanoTime()));
        String template = template(author(setting).browser(), "WI", "PRD");
        grant(setting, template, group);

        assertThat(revoke(setting, template, Grantee.user(group))).as("a user with that identifier is no group")
                .isFalse();
        assertThat(shares(setting.organization(), template)).isEqualTo(1);
        assertThat(revoke(setting, template, new Grantee(Grantee.Kind.GROUP, group))).isTrue();
        assertThat(revoke(setting, template, new Grantee(Grantee.Kind.GROUP, group))).isFalse();

        assertThat(shares(setting.organization(), template)).isZero();
        assertThat(audited(setting.organization(), "data.record.unshared")).isEqualTo(1);
    }

    @Test
    void theAuditRecordsNameTheKindAndTheLevelAndNeverAGroupOrAPerson() {
        Setting setting = setting();
        UUID group = UUID.fromString(group(setting.admin(), "SecretGroupName" + System.nanoTime()));
        String template = template(author(setting).browser(), "WI", "PRD");
        grant(setting, template, group);

        String attributes = owner("select string_agg(attributes::text, ' ') from audit_record where event_type = "
                + "'data.record.shared' and context_tenant_id = ?", setting.organization().id().value()).get(0);

        assertThat(attributes).contains("GROUP").contains("READ").doesNotContain("SecretGroupName")
                .doesNotContain(group.toString());
    }
}
