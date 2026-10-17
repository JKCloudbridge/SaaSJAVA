package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The events the relay gave up on that workflows depend on (ADR-0143): a parked {@code metadata.published} event
 * leaves a scheduled workflow without its schedule row until it is put back; it is listed (never its payload), put
 * back by a person with the ability to replay, and putting it back twice makes one schedule row. Another organization
 * sees none of it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowParkedEventsIT extends ActionsSupport {

    private static final String SCHEDULED = "{\"kind\":\"EVERY_DAY\",\"timeZone\":\"UTC\",\"time\":\"09:00\"}";

    private record Setup(Organization organization, TestBrowser admin) {
    }

    /** A scheduled workflow whose release event was parked before the schedule row was made. */
    private Setup parkedRelease() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Nightly", SCHEDULED, null, action("Tell", "night.event"));
        settle(organization);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(
                () -> scalar(organization, "select count(*) from workflow_schedule") == 1);
        // As if the handler of this event had failed until the relay parked it: no row, no marker, a dead event.
        run(organization, "delete from workflow_schedule");
        run(organization, "delete from processed_event where consumer = 'workflow.schedules'");
        run(organization, "update outbox_event set status = 'DEAD', last_error_type = 'SomeFailure', attempts = 10, "
                + "version = version + 1 where event_type = 'metadata.published' and created_at = "
                + "(select max(created_at) "
                + "from outbox_event where event_type = 'metadata.published')");
        return new Setup(organization, admin);
    }

    @Test
    void aParkedReleaseIsListedWithoutItsPayloadAndPuttingItBackMakesTheScheduleRow() {
        Setup setup = parkedRelease();

        Response listed = setup.admin().get(PARKED);
        String event = JsonPath.read(listed.body(), "$.data[0].id");
        Response redriven = setup.admin().postJson(PARKED + "/" + event + "/redrive", "{}");
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(
                () -> scalar(setup.organization(), "select count(*) from workflow_schedule") == 1);
        settle(setup.organization());

        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(listed.body(), "$.data[0].type")).isEqualTo("metadata.published");
        assertThat(JsonPath.<String>read(listed.body(), "$.data[0].lastFailureType")).isEqualTo("SomeFailure");
        assertThat(JsonPath.<Integer>read(listed.body(), "$.data[0].attempts")).isEqualTo(10);
        assertThat(listed.body()).doesNotContain("payload");
        assertThat(redriven.status()).as(redriven.body()).isEqualTo(204);
        assertThat(JsonPath.<List<String>>read(setup.admin().get(PARKED).body(), "$.data[*].id")).isEmpty();
    }

    @Test
    void puttingItBackTwiceChangesNothingTheSecondTimeAndMakesOneRow() {
        Setup setup = parkedRelease();
        String event = JsonPath.read(setup.admin().get(PARKED).body(), "$.data[0].id");

        Response first = setup.admin().postJson(PARKED + "/" + event + "/redrive", "{}");
        Response second = setup.admin().postJson(PARKED + "/" + event + "/redrive", "{}");
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(
                () -> scalar(setup.organization(), "select count(*) from workflow_schedule") == 1);
        settle(setup.organization());

        assertThat(first.status()).isEqualTo(204);
        assertThat(second.status()).isEqualTo(404);
        assertThat(scalar(setup.organization(), "select count(*) from workflow_schedule")).isEqualTo(1);
    }

    @Test
    void aParkedEventOfAnotherKindIsNotListedAndCannotBePutBackThroughThisDoor() {
        Setup setup = parkedRelease();
        run(setup.organization(), "update outbox_event set event_type = 'tenant.activated' where status = 'DEAD'");

        assertThat(JsonPath.<List<String>>read(setup.admin().get(PARKED).body(), "$.data[*].id")).isEmpty();
        String dead = owner("select id::text from outbox_event where status = 'DEAD' and tenant_id = cast(? as uuid)",
                setup.organization().id().value().toString()).get(0);
        assertThat(setup.admin().postJson(PARKED + "/" + dead + "/redrive", "{}").status()).isEqualTo(404);
    }

    @Test
    void anotherOrganizationNeitherSeesNorPutsBackAnEventOfThisOne() {
        Setup mine = parkedRelease();
        Organization theirs = organization();
        TestBrowser other = adminWithData(theirs);
        String event = JsonPath.read(mine.admin().get(PARKED).body(), "$.data[0].id");

        assertThat(JsonPath.<List<String>>read(other.get(PARKED).body(), "$.data[*].id")).isEmpty();
        assertThat(other.postJson(PARKED + "/" + event + "/redrive", "{}").status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(mine.admin().get(PARKED).body(), "$.data[*].id")).containsExactly(event);
    }

    @Test
    void listingNeedsTheAbilityToViewAndPuttingBackNeedsTheAbilityToReplay() {
        Setup setup = parkedRelease();
        String event = JsonPath.read(setup.admin().get(PARKED).body(), "$.data[0].id");
        TestBrowser nobody = as(setup.organization(), memberWith(setup.organization(), setup.admin()));
        TestBrowser viewer = as(setup.organization(), memberWith(setup.organization(), setup.admin(),
                "workflows.view"));
        TestBrowser replayer = as(setup.organization(), memberWith(setup.organization(), setup.admin(),
                "workflows.replay"));

        assertThat(nobody.get(PARKED).status()).isEqualTo(403);
        assertThat(viewer.get(PARKED).status()).isEqualTo(200);
        assertThat(viewer.postJson(PARKED + "/" + event + "/redrive", "{}").status()).isEqualTo(403);
        assertThat(replayer.postJson(PARKED + "/" + event + "/redrive", "{}").status()).isEqualTo(204);
    }

    @Test
    void aRedriveIsAuditedByTheIdentifierOfTheEventOnly() {
        Setup setup = parkedRelease();
        String event = JsonPath.read(setup.admin().get(PARKED).body(), "$.data[0].id");

        setup.admin().postJson(PARKED + "/" + event + "/redrive", "{}");

        assertThat(owner("select attributes::text from audit_record where event_type = 'workflow.event.redriven'"))
                .singleElement().satisfies(audit -> assertThat(audit).contains(event));
    }
}
