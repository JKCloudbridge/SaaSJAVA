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
 * Two real application instances at once on one fill-in (FF1, ADR-0192): the same fill-in saved from both with the same
 * row version, a save at the moment it is sent, the same fill-in sent from both, and two people starting while the
 * version in use is replaced. Whatever the order, the cells of a fill-in that was sent are the cells that were sent,
 * one
 * request wins and the other is told so in plain words. Each racing browser is a different account.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class FillInRacesIT extends FillSupport {

    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    private record Run(World world, TestBrowser fillerOnB, TestBrowser approverOnB) {
    }

    private Run run() {
        World world = world();
        return new Run(world,
                TestOrganizations.signedIn(instanceB.port(), world.setting().organization().host(),
                        world.filler().member().person()),
                TestOrganizations.signedIn(instanceB.port(), world.setting().organization().host(),
                        world.approver().member().person()));
    }

    @Test
    void theSameFillInSavedFromTwoInstancesWithOneRowVersionHasOneWinner() {
        Run run = run();
        World world = run.world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> saveCells(world.filler().browser(), fill,
                version, cells(row("r1", "\"t\":\"from A\""))));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> saveCells(run.fillerOnB(), fill, version,
                cells(row("r1", "\"t\":\"from B\""))));

        List<Integer> answers = List.of(a.join().status(), b.join().status());
        assertThat(answers).as("one wins, the other is told it changed").containsExactlyInAnyOrder(200, 409);
        String kept = JsonPath.read(fillView(world.filler().browser(), fill).body(), "$.data.cells.rows[0].cells.t");
        assertThat(kept).isIn("from A", "from B");
        assertThat(scalar(world.setting().organization(), "select count(*) from sheet_fill where fill_id = ?",
                java.util.UUID.fromString(fill))).isEqualTo(1);
    }

    @Test
    void aSaveAtTheMomentOfSendingEndsInACoherentFillIn() {
        Run run = run();
        World world = run.world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        long version = fillVersion(fillView(world.filler().browser(), fill));
        String changed = cells(row("r1", "\"t\":\"Changed at the last moment\""), row("r2", "\"t\":\"Bell works\""));

        CompletableFuture<Response> save = CompletableFuture.supplyAsync(() -> saveCells(world.filler().browser(),
                fill, version, changed));
        CompletableFuture<Response> send = CompletableFuture.supplyAsync(() -> sendFill(run.fillerOnB(), fill,
                world.process()));

        int saveStatus = save.join().status();
        int sendStatus = send.join().status();
        assertThat(sendStatus).as("the fill-in was complete either way").isEqualTo(201);
        assertThat(saveStatus).as("saved before it was sent, or refused after").isIn(200, 403, 409);
        Response view = fillView(world.filler().browser(), fill);
        assertThat(JsonPath.<String>read(view.body(), "$.data.fill.status")).isEqualTo("SUBMITTED");
        String t = JsonPath.read(view.body(), "$.data.cells.rows[0].cells.t");
        assertThat(t).as("what was sent is what is stored: the change either got in first or never did")
                .isEqualTo(saveStatus == 200 ? "Changed at the last moment" : "Door open");
    }

    @Test
    void theSameFillInSentFromTwoInstancesMakesOneRequest() {
        Run run = run();
        World world = run.world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> sendFill(world.filler().browser(), fill,
                world.process()));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> sendFill(run.fillerOnB(), fill,
                world.process()));

        Response one = a.join();
        Response two = b.join();
        assertThat(one.status()).as(one.body()).isEqualTo(201);
        assertThat(two.status()).as("the same person sending twice is told the same request").isEqualTo(201);
        assertThat(JsonPath.<String>read(one.body(), "$.data.summary.id"))
                .isEqualTo(JsonPath.<String>read(two.body(), "$.data.summary.id"));
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
        assertThat(scalar(world.setting().organization(), "select count(*) from approval_instance where record_id = ?",
                java.util.UUID.fromString(fill))).isEqualTo(1);
    }

    @Test
    void twoReviewersDecidingTheSameFillInAtOnceEndItOnce() {
        Run run = run();
        World world = run.world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());

        CompletableFuture<Response> approve = CompletableFuture.supplyAsync(() -> approve(
                world.approver().browser(), request));
        CompletableFuture<Response> reject = CompletableFuture.supplyAsync(() -> reject(run.approverOnB(), request));

        assertThat(List.of(approve.join().status(), reject.join().status())).containsExactlyInAnyOrder(200, 409);
        assertThat(fillStatus(world.filler().browser(), fill)).isIn("APPROVED", "RETURNED");
        assertThat(headerStatus(world.filler().browser(), fill)).as("the copy on the header agrees")
                .isEqualTo(fillStatus(world.filler().browser(), fill));
    }

    @Test
    void peopleStartingWhileANewVersionIsPublishedEachEndOnAVersionThatWasInUse() {
        Run run = run();
        World world = run.world();

        CompletableFuture<Integer> publish = CompletableFuture.supplyAsync(() -> nextVersionInUse(world));
        CompletableFuture<Response> mine = CompletableFuture.supplyAsync(() -> startFill(world.filler().browser(),
                world.template()));
        CompletableFuture<Response> theirs = CompletableFuture.supplyAsync(() -> startFill(run.fillerOnB(),
                world.template()));

        publish.join();
        for (Response answer : List.of(mine.join(), theirs.join())) {
            assertThat(answer.status()).as(answer.body()).isIn(201, 409);
            if (answer.status() == 201) {
                int version = JsonPath.<Integer>read(answer.body(), "$.data.versionNo");
                assertThat(version).as("never a draft or an approved version").isIn(1, 2);
            }
        }
        assertThat(scalar(world.setting().organization(), "select count(*) from sheet_fill f where not exists "
                + "(select 1 from sheet_version v where v.owner_id = f.owner_id and v.version_no = f.version_no)",
                new Object[0])).isZero();
    }
}
