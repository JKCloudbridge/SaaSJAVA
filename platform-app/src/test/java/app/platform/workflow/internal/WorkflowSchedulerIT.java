package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The scheduler of timed workflows (ADR-0134), on the real outbox and database, driven by the test: the schedule row
 * follows the published workflow (made, started over when the definition changes, removed when the workflow is switched
 * off), a due schedule fires once per record and not again, a doubled or resumed firing makes no second execution,
 * three instances never fire one period twice, a one-time moment fires once and is done, and the days-after trigger
 * loses no record to downtime. The rules about daylight saving and zones are unit tests of {@code ScheduleMath}.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false",
    "platform.workflow.scheduler.page-size=2"})
class WorkflowSchedulerIT extends WorkflowSupport {

    private static TestApplication instanceB;
    private static TestApplication instanceC;

    @BeforeAll
    static void startTheOtherInstances() {
        String[] settings = {"platform.outbox.enabled=true", "platform.outbox.poll-interval=50ms",
            "platform.workflow.enabled=false", "platform.workflow.scheduler.page-size=2"};
        instanceB = TestApplication.start(settings);
        instanceC = TestApplication.start(settings);
    }

    @AfterAll
    static void stopTheOtherInstances() {
        instanceC.close();
        instanceB.close();
    }

    private static String daily(String zone, String time) {
        return "{\"kind\":\"EVERY_DAY\",\"timeZone\":\"" + zone + "\",\"time\":\"" + time + "\"}";
    }

    private static String dailyWithSelection(String field, String value) {
        return "{\"kind\":\"EVERY_DAY\",\"timeZone\":\"UTC\",\"time\":\"09:00\",\"selection\":{\"op\":\"EQ\","
                + "\"field\":\"" + field + "\",\"values\":[\"" + value + "\"]}}";
    }

    private static void makeDue(Organization organization) {
        run(organization, "update workflow_schedule set next_fire_at = now() - interval '1 minute', "
                + "locked_until = null, version = version + 1");
    }

    private static List<Map<String, String>> schedules(Organization organization) {
        return rows(organization, "select state, trigger_kind, definition_version, "
                + "case when next_fire_at > now() then 'true' else 'false' end as in_future, "
                + "case when last_fired_at is not null then 'true' else 'false' end as fired, "
                + "case when firing_for is not null then 'true' else 'false' end as firing from workflow_schedule");
    }

    private static List<Map<String, String>> firings(Organization organization) {
        return executions(organization).stream().filter(row -> "true".equals(row.get("from_firing"))).toList();
    }

    // ---- the schedule follows the workflow ----

    @Test
    void aScheduledWorkflowGetsARowStartedOverByANewDefinitionAndRemovedWhenSwitchedOff() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);

        String name = workflowExists(admin, "Daily", daily("UTC", "09:00"), null, action("Tell", "daily.event"));
        settle(organization);
        assertThat(schedules(organization)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("state", "WAITING").containsEntry("trigger_kind", "EVERY_DAY")
                .containsEntry("definition_version", "1").containsEntry("in_future", "true")
                .containsEntry("fired", "false"));

        change(admin, name, daily("Asia/Tokyo", "03:00"), null, true, action("Tell", "daily.event"));
        settle(organization);
        assertThat(schedules(organization)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("definition_version", "2").containsEntry("state", "WAITING"));

        switchTo(admin, name, false);
        settle(organization);
        assertThat(schedules(organization)).as("a switched-off workflow fires nothing").isEmpty();

        switchTo(admin, name, true);
        settle(organization);
        assertThat(schedules(organization)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("definition_version", "2").containsEntry("in_future", "true"));
    }

    @Test
    void aRecordTriggerAndARemovedWorkflowLeaveNoSchedule() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "OnCreate", recordTrigger("RECORD_CREATED"), null, action("Tell", "a.b"));
        String daily = workflowExists(admin, "Daily", daily("UTC", "09:00"), null, action("Tell", "daily.event"));
        settle(organization);
        assertThat(schedules(organization)).hasSize(1);

        admin.request("DELETE", WORKFLOWS + "/" + OBJECT + "/" + daily, null);
        settle(organization);

        assertThat(schedules(organization)).isEmpty();
    }

    // ---- a firing ----

    @Test
    void aDueScheduleMakesOneExecutionPerRecordThatMeetsTheConditionAndAFiringDoneTwiceMakesNoSecond() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        for (int quantity : List.of(5, 20, 30)) {
            record(admin, "{\"fieldA__c\":\"r" + quantity + "\",\"quantity__c\":" + quantity + "}");
        }
        workflowExists(admin, "Daily", daily("UTC", "09:00"), "quantity__c > 10", action("Tell", "daily.event"));
        settle(organization);
        makeDue(organization);

        int claimed = dispatcher.pollSchedulesOnce();

        assertThat(claimed).isGreaterThanOrEqualTo(1);
        assertThat(firings(organization)).as("two of the three records meet the condition").hasSize(2);
        assertThat(schedules(organization)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("state", "WAITING").containsEntry("in_future", "true").containsEntry("fired", "true")
                .containsEntry("firing", "false"));
        dispatcher.pollSchedulesOnce();
        assertThat(firings(organization)).as("not due again").hasSize(2);

        // The same firing is done again (a retry, a second instance with a stale claim): the keys are the same.
        run(organization, "update workflow_schedule set next_fire_at = last_fired_at, state = 'WAITING', "
                + "locked_until = null, version = version + 1");
        dispatcher.pollSchedulesOnce();
        assertThat(firings(organization)).as("the same firing, the same executions").hasSize(2);
        assertThat(scalar(organization, "select count(distinct idempotency_key) from workflow_execution"))
                .isEqualTo(2);
    }

    @Test
    void aFiringThatDiedHalfWayIsTakenOverWhereItStoppedAndMakesNothingTwice() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        for (int i = 0; i < 5; i++) {
            record(admin, "{\"fieldA__c\":\"r" + i + "\"}");
        }
        workflowExists(admin, "Daily", daily("UTC", "09:00"), null, action("Tell", "daily.event"));
        settle(organization);
        // An instance made the executions of the first two records (page size two), wrote how far it got and died.
        run(organization, "update workflow_schedule set state = 'FIRING', firing_for = now() - interval '1 hour', "
                + "firing_after = (select id from object_record where object_api_name = 'ObjectA__c' "
                + "order by id offset 1 limit 1), next_fire_at = now() - interval '1 minute', "
                + "locked_until = now() - interval '1 second', version = version + 1");

        dispatcher.pollSchedulesOnce();

        List<String> done = rows(organization, "select record_id::text as id from workflow_execution")
                .stream().map(row -> row.get("id")).toList();
        List<String> firstTwo = rows(organization, "select id::text as id from object_record "
                + "where object_api_name = 'ObjectA__c' order by id limit 2").stream().map(row -> row.get("id"))
                .toList();
        assertThat(done).hasSize(3).doesNotContainAnyElementsOf(firstTwo);
        assertThat(schedules(organization)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("state", "WAITING").containsEntry("firing", "false"));
    }

    @Test
    void threeInstancesPollingTheSameDueScheduleFireItOnce() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        for (int i = 0; i < 6; i++) {
            record(admin, "{\"fieldA__c\":\"r" + i + "\"}");
        }
        workflowExists(admin, "Daily", daily("UTC", "09:00"), null, action("Tell", "daily.event"));
        settle(organization);
        makeDue(organization);
        List<Dispatcher> dispatchers = List.of(dispatcher, instanceB.bean(Dispatcher.class),
                instanceC.bean(Dispatcher.class));
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            List<Future<Integer>> polls = new ArrayList<>();
            for (Dispatcher one : dispatchers) {
                polls.add(pool.submit(one::pollSchedulesOnce));
            }
            for (Future<Integer> poll : polls) {
                poll.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(firings(organization)).hasSize(6);
        assertThat(scalar(organization, "select count(distinct record_id) from workflow_execution")).isEqualTo(6);
        assertThat(schedules(organization)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("state", "WAITING").containsEntry("fired", "true"));
    }

    // ---- the kinds ----

    @Test
    void aSelectionNarrowsTheRecordsAFiringConsiders() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        record(admin, "{\"fieldA__c\":\"keep\"}");
        record(admin, "{\"fieldA__c\":\"drop\"}");
        record(admin, "{\"fieldA__c\":\"keep\"}");
        workflowExists(admin, "Picked", dailyWithSelection("fieldA__c", "keep"), null, action("Tell", "daily.event"));
        settle(organization);
        makeDue(organization);

        dispatcher.pollSchedulesOnce();

        assertThat(firings(organization)).hasSize(2);
        assertThat(scalar(organization, "select count(*) from workflow_execution e join object_record r on "
                + "r.id = e.record_id where r.payload ->> 'fieldA__c' = 'drop'")).isZero();
    }

    @Test
    void aOneTimeMomentFiresOnceAndThenTheScheduleIsDone() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        record(admin, "{\"fieldA__c\":\"x\"}");
        String at = LocalDateTime.now(ZoneOffset.UTC).plusHours(2).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        workflowExists(admin, "Once", "{\"kind\":\"DATE_TIME\",\"timeZone\":\"UTC\",\"at\":\"" + at + "\"}", null,
                action("Tell", "once.event"));
        settle(organization);
        assertThat(schedules(organization)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("state", "WAITING").containsEntry("trigger_kind", "DATE_TIME"));
        makeDue(organization);

        dispatcher.pollSchedulesOnce();
        dispatcher.pollSchedulesOnce();

        assertThat(firings(organization)).hasSize(1);
        assertThat(schedules(organization)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("state", "DONE").containsEntry("fired", "true"));
    }

    @Test
    void aMomentThatIsNotInTheFutureIsRefusedWhenTheWorkflowIsMade() {
        TestBrowser admin = adminWithData(organization());
        String past = LocalDateTime.now(ZoneOffset.UTC).minusHours(1).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

        Response refused = createWorkflow(admin, "Late", "{\"kind\":\"DATE_TIME\",\"timeZone\":\"UTC\",\"at\":\""
                + past + "\"}", null, action("Tell", "late.event"));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("trigger.at").contains("future");
    }

    @Test
    void theDaysAfterTriggerFiresForTheRecordsWhoseDateHasComeAndLosesNoneToDowntime() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int ago : List.of(10, 6, 5, 4, 3, 2, 0)) {
            record(admin, "{\"fieldA__c\":\"ago" + ago + "\",\"closeDate__c\":\"" + today.minusDays(ago) + "\"}");
        }
        workflowExists(admin, "Followup", "{\"kind\":\"DAYS_AFTER_DATE\",\"field\":\"closeDate__c\",\"days\":3,"
                + "\"timeZone\":\"UTC\",\"time\":\"00:00\"}", null, action("Tell", "followup.event"));
        settle(organization);
        // First firing: only the record whose date is exactly three days back.
        makeDue(organization);
        dispatcher.pollSchedulesOnce();
        assertThat(datesFired(organization)).containsExactly("ago3");

        // The platform was down for two days: the schedule last covered the period ending three days ago.
        run(organization, "delete from workflow_execution");
        run(organization, "update workflow_schedule set covered_through = now() - interval '3 days', "
                + "next_fire_at = now() - interval '1 minute', state = 'WAITING', locked_until = null, "
                + "version = version + 1");
        dispatcher.pollSchedulesOnce();

        assertThat(datesFired(organization)).as("the dates that came due while nothing ran, none twice")
                .containsExactlyInAnyOrder("ago3", "ago4", "ago5");
    }

    private static List<String> datesFired(Organization organization) {
        return rows(organization, "select r.payload ->> 'fieldA__c' as name from workflow_execution e "
                + "join object_record r on r.id = e.record_id").stream().map(row -> row.get("name")).toList();
    }
}
