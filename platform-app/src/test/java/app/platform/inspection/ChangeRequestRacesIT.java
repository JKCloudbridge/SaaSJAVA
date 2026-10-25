package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Two real application instances at once on one template (FA5, ADR-0191; FA8, ADR-0202): the same change asked for from
 * both, and the same revised copy made from both. Whatever the order, one wins and the other is told so in plain words;
 * there is one request in progress, and one copy. Each racing browser is a different account.
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

    private record Run(World world, TestBrowser readerOnB, TestBrowser approverOnB) {
    }

    private Run run() {
        World world = world();
        TestBrowser readerOnB = TestOrganizations.signedIn(instanceB.port(), world.setting().organization().host(),
                world.colleague().member().person());
        TestBrowser approverOnB = TestOrganizations.signedIn(instanceB.port(), world.setting().organization().host(),
                world.approver().member().person());
        return new Run(world, readerOnB, approverOnB);
    }

    @Test
    void theSameTemplateAskedAboutFromTwoInstancesMakesOneRequestAndOneWinner() {
        Run run = run();
        World world = run.world();

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> ask(world.reader().browser(),
                world.template(), "From A", 1));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> ask(run.readerOnB(), world.template(),
                "From B", 1));

        List<Integer> answers = List.of(a.join().status(), b.join().status());
        assertThat(answers).as("one wins, the other is told a change is in progress")
                .containsExactlyInAnyOrder(201, 409);
        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_change_request where "
                + "template_id = ?", UUID.fromString(world.template()))).isEqualTo(1);
        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_change_register "
                + "where tenant_id = ?", world.setting().organization().id().value())).isEqualTo(1);
    }

    @Test
    void theSameRevisedCopyMadeFromTwoInstancesMakesOneCopyAndOneWinner() {
        Run run = run();
        World world = run.world();
        String request = asked(world.reader().browser(), world.template(), "Once", 1);

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> createCopy(world.approver().browser(),
                world.template(), request));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> createCopy(run.approverOnB(),
                world.template(), request));

        List<Integer> answers = List.of(a.join().status(), b.join().status());
        assertThat(answers).as("one wins, the other is told the copy exists").containsExactlyInAnyOrder(200, 409);
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("DRAFT", "ACTIVE");
        assertThat(scalar(world.setting().organization(), "select count(*) from sheet_version where owner_id = ? "
                + "and status = 'DRAFT'", UUID.fromString(world.template()))).isEqualTo(1);
    }
}
