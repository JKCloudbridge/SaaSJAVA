package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Two real application instances at once on one template (FA4, ADR-0188): the same submit from both, the same publish
 * from both, publishes of different audiences from both, and two approvers deciding the same step at the same time.
 * Whatever the order, the database ends in a state the lifecycle allows: one request, one live publication for a group,
 * shares that match the publications, one number.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateRacesIT extends TemplateSupport {

    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    private record Run(Setting setting, Person author, Person first, Person second, TestBrowser firstOnB,
            TestBrowser secondOnB, TestBrowser authorOnB, String process, String plantGroup, String headGroup) {
    }

    private Run run() {
        Setting setting = setting();
        Person author = author(setting);
        Person first = approver(setting);
        Person second = approver(setting);
        String process = process(setting.admin(), "Either", step("Only", user(first.userId()), user(second.userId())));
        Person reader = reader(setting);
        String plant = group(setting.admin(), "plant-" + System.nanoTime(), reader);
        String head = group(setting.admin(), "head-" + System.nanoTime(), reader);
        department(setting.admin(), "PLANT1", "PRD", plant);
        department(setting.admin(), "HEAD", "HR", head);
        String host = setting.organization().host();
        return new Run(setting, author, first, second,
                TestOrganizations.signedIn(instanceB.port(), host, first.member().person()),
                TestOrganizations.signedIn(instanceB.port(), host, second.member().person()),
                TestOrganizations.signedIn(instanceB.port(), host, author.member().person()), process, plant, head);
    }

    private String approved(Run run) {
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        String request = submitted(run.author().browser(), template, run.process());
        assertThat(approve(run.first().browser(), request).status()).isEqualTo(200);
        return template;
    }

    @Test
    void theSameSubmitFromTwoInstancesMakesOneRequestAndBothAreToldWhich() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> submit(run.author().browser(), template,
                run.process()));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> submit(run.authorOnB(), template,
                run.process()));

        Response one = a.join();
        Response two = b.join();
        assertThat(one.status()).as(one.body()).isEqualTo(201);
        assertThat(two.status()).as(two.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(one.body(), "$.data.summary.id"))
                .isEqualTo(JsonPath.<String>read(two.body(), "$.data.summary.id"));
        assertThat(scalar(run.setting().organization(), "select count(*) from approval_instance where record_id = ?",
                java.util.UUID.fromString(template))).isEqualTo(1);
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW");
    }

    @Test
    void twoApproversDecidingTheSameStepAtOnceGiveOneNumberAndOneDecision() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        String request = submitted(run.author().browser(), template, run.process());

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> approve(run.first().browser(), request));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> approve(run.secondOnB(), request));

        List<Integer> answers = List.of(a.join().status(), b.join().status());
        assertThat(answers).as("one decides, the other finds it decided").contains(200);
        assertThat(answers.stream().filter(status -> status == 200).count()).isEqualTo(1);
        assertThat(number(run.setting().admin(), template)).isEqualTo("WI/PRD/01");
        assertThat(statuses(run.author().browser(), template)).containsExactly("APPROVED");
        assertThat(scalar(run.setting().organization(), "select next_value from number_series_counter "
                + "where series_key = 'document' and scope_key = 'WI/PRD'")).isEqualTo(2);
    }

    @Test
    void theSamePublishFromTwoInstancesLeavesOnePublicationAndOneShare() {
        Run run = run();
        String template = approved(run);

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> publish(run.first().browser(), template,
                null, "PLANT1/PRD"));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> publish(run.secondOnB(), template, null,
                "PLANT1/PRD"));

        assertThat(a.join().status()).isEqualTo(200);
        assertThat(b.join().status()).isEqualTo(200);
        assertThat(publications(run.setting().organization(), template, true)).isEqualTo(1);
        assertThat(publications(run.setting().organization(), template, false)).isEqualTo(1);
        assertThat(shares(run.setting().organization(), template)).isEqualTo(1);
        assertThat(statuses(run.author().browser(), template)).containsExactly("ACTIVE");
    }

    @Test
    void publishesOfDifferentAudiencesFromTwoInstancesEndWithTheLaterOneAndSharesThatMatch() {
        Run run = run();
        String template = approved(run);

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> publish(run.first().browser(), template,
                null, "PLANT1/PRD"));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> publish(run.secondOnB(), template, null,
                "HEAD/HR"));

        assertThat(a.join().status()).isEqualTo(200);
        assertThat(b.join().status()).isEqualTo(200);
        assertThat(publications(run.setting().organization(), template, true)).as("one audience, never both")
                .isEqualTo(1);
        assertThat(shares(run.setting().organization(), template)).as("the shares are the live audience")
                .isEqualTo(1);
        assertThat(scalar(run.setting().organization(), "select count(*) from record_share s join "
                + "inspection_publication p on p.group_id = s.grantee_id and p.template_id = s.record_id "
                + "where s.record_id = ? and p.revoked_at is null", java.util.UUID.fromString(template))).isEqualTo(1);
    }

    @Test
    void aPublishAndAnUnpublishAtOnceEndInAStateTheLifecycleAllows() {
        Run run = run();
        String template = approved(run);
        assertThat(publish(run.first().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> unpublish(run.first().browser(),
                template));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> publish(run.secondOnB(), template, null,
                "HEAD/HR"));

        a.join();
        b.join();
        List<String> statuses = statuses(run.author().browser(), template);
        long live = publications(run.setting().organization(), template, true);
        long shares = shares(run.setting().organization(), template);
        assertThat(statuses).hasSize(1);
        assertThat(live).isEqualTo(shares);
        assertThat(statuses.get(0).equals("ACTIVE") ? live == 1 : live == 0).as(statuses + " with " + live).isTrue();
    }
}
