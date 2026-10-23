package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Two real application instances at once on one change request (FA5, ADR-0191): the same request turned into a version
 * from both, the same answer from both, and two requests turned together. Whatever the order, one wins and the other is
 * told so in plain words; there is one draft and the request names it. Each racing browser is a different account.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ChangeRequestRacesIT extends ChangeRequestSupport {

    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    private record Run(World world, TestBrowser approverOnB) {
    }

    private Run run() {
        World world = world();
        TestBrowser approverOnB = TestOrganizations.signedIn(instanceB.port(), world.setting().organization().host(),
                world.approver().member().person());
        return new Run(world, approverOnB);
    }

    @Test
    void theSameRequestTurnedIntoAVersionFromTwoInstancesMakesOneDraftAndOneWinner() {
        Run run = run();
        World world = run.world();
        String request = asked(world.reader().browser(), world.template(), "Once", 1);

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> turn(world.author().browser(),
                world.template(), request));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> turn(run.approverOnB(),
                world.template(), request));

        List<Integer> answers = List.of(a.join().status(), b.join().status());
        assertThat(answers).as("one wins, the other is told it was already turned").containsExactlyInAnyOrder(200, 409);
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("DRAFT", "ACTIVE");
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("DONE");
        assertThat(scalar(world.setting().organization(), "select count(*) from sheet_version where owner_id = ? "
                + "and status = 'DRAFT'", java.util.UUID.fromString(world.template()))).isEqualTo(1);
        assertThat(scalar(world.setting().organization(), "select resulting_version from inspection_change_request "
                + "where id = ?", java.util.UUID.fromString(request))).isEqualTo(2);
    }

    @Test
    void theSameAnswerFromTwoInstancesIsGivenOnce() {
        Run run = run();
        World world = run.world();
        String request = asked(world.reader().browser(), world.template(), "Once", 1);

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> answerRequest(world.author().browser(),
                world.template(), request, "ACCEPT", null));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> answerRequest(run.approverOnB(),
                world.template(), request, "ACCEPT", null));

        assertThat(List.of(a.join().status(), b.join().status())).containsExactlyInAnyOrder(200, 409);
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("ACCEPTED");
        assertThat(audited(world.setting().organization(), "inspection.changerequest.accepted")).isEqualTo(1);
    }

    @Test
    void anAnswerAndATurnAtOnceEndInAStateTheLifecycleAllows() {
        Run run = run();
        World world = run.world();
        String request = asked(world.reader().browser(), world.template(), "Either", 1);

        CompletableFuture<Response> decline = CompletableFuture.supplyAsync(() -> answerRequest(
                world.author().browser(), world.template(), request, "DECLINE", "No"));
        CompletableFuture<Response> turn = CompletableFuture.supplyAsync(() -> turn(run.approverOnB(),
                world.template(), request));

        int declined = decline.join().status();
        int turned = turn.join().status();
        assertThat(List.of(declined, turned)).containsExactlyInAnyOrder(200, 409);
        String status = requestStatus(world.reader().browser(), world.template(), request);
        assertThat(status).isIn("DECLINED", "DONE");
        assertThat(statuses(world.author().browser(), world.template()))
                .as("a draft exists exactly when the request was turned")
                .hasSize("DONE".equals(status) ? 2 : 1);
    }
}
