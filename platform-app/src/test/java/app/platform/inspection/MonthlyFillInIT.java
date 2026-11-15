package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The monthly fill-in (MC2A, ADR-0260), on a real PostgreSQL and the real application: a plant head starts one for
 * their plant and a month, the header carries the plant and the month, one exists for a document, a plant and a month
 * (the application says so in words and the database holds the same when two start together), the plant mates read
 * it and cannot change it, a plant head of another plant sees nothing of it, a future month and a plant that is not
 * theirs are refused, and the fill-ins of every other application are untouched.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class MonthlyFillInIT extends PlantsSupport {

    private static final String THIS_MONTH = YearMonth.now(ZoneOffset.UTC).toString();

    private Plants published() {
        Plants plants = plants();
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA())),
                plant("PLANT2", reviewerUser(plants.reviewerB()))).status()).isEqualTo(200);
        return plants;
    }

    private static String fieldOf(TestBrowser browser, String fill, String field) {
        Object value = headerField(browser, fill, field);
        return value == null ? null : value.toString();
    }

    @Test
    void aPlantHeadStartsTheMonthOfTheirPlantAndTheHeaderCarriesThePlantAndTheMonth() {
        Plants plants = published();
        Response options = plants.head1().browser().get(TEMPLATES + "/" + plants.template() + "/fill-in-options");
        assertThat(options.status()).as(options.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(options.body(), "$.data.canStart")).isTrue();
        assertThat(JsonPath.<Boolean>read(options.body(), "$.data.monthly")).isTrue();
        assertThat(JsonPath.<List<String>>read(options.body(), "$.data.plants[*].plant")).containsExactly("PLANT1");
        assertThat(JsonPath.<String>read(options.body(), "$.data.currentPeriod")).isEqualTo(THIS_MONTH);

        // A person of one plant is given their plant; the month is this month.
        Response started = startMonth(plants.head1().browser(), plants.template(), "{}");
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        String fill = JsonPath.read(started.body(), "$.data.fillId");
        assertThat(fieldOf(plants.head1().browser(), fill, "plant")).isEqualTo("PLANT1");
        assertThat(fieldOf(plants.head1().browser(), fill, "period")).isEqualTo(THIS_MONTH);
        assertThat(fieldOf(plants.head1().browser(), fill, "application")).isEqualTo(CORE);

        Response row = plants.head1().browser().get(FILL_INS);
        assertThat(JsonPath.<List<String>>read(row.body(), "$.data.rows[*].plant")).containsExactly("PLANT1");
        assertThat(JsonPath.<List<String>>read(row.body(), "$.data.rows[*].period")).containsExactly(THIS_MONTH);
    }

    @Test
    void oneFillInExistsForADocumentAPlantAndAMonthAndSaysSoInWords() {
        Plants plants = published();
        String first = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", "2026-01");

        Response again = startMonth(plants.head1().browser(), plants.template(),
                "{\"plant\":\"PLANT1\",\"period\":\"2026-01\"}");
        Response mate = startMonth(plants.head1b().browser(), plants.template(),
                "{\"plant\":\"PLANT1\",\"period\":\"2026-01\"}");

        assertThat(again.status()).as(again.body()).isEqualTo(409);
        assertThat(mate.status()).isEqualTo(409);
        assertThat(message(again)).isEqualTo(message(mate)).contains("exists already");
        assertThat(message(again)).as("no identifier, owner or name in the words").doesNotContain(first);
        assertThat(listedFills(plants.world().approver().browser(), "")).containsExactly(first);
        // Another month, and the same month of another plant, are different fill-ins.
        startedMonth(plants.head1().browser(), plants.template(), "PLANT1", "2026-02");
        startedMonth(plants.head2().browser(), plants.template(), "PLANT2", "2026-01");
        assertThat(scalar(plants.world().setting().organization(),
                "select count(*) from inspection_fill_period where template_id = ?",
                UUID.fromString(plants.template()))).isEqualTo(3);
    }

    @Test
    void aRejectedFillInIsReturnedAndEditedNeverMadeAgain() {
        Plants plants = published();
        String fill = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", "2026-01");
        saved(plants.head1().browser(), fill, complete());
        sent(plants.head1().browser(), fill, plants.process());
        String request = tasks(plants.reviewerA().browser()).get(0);
        assertThat(reject(plants.reviewerA().browser(), request).status()).isEqualTo(200);

        assertThat(fillStatus(plants.head1().browser(), fill)).isEqualTo("RETURNED");
        Response again = startMonth(plants.head1().browser(), plants.template(),
                "{\"plant\":\"PLANT1\",\"period\":\"2026-01\"}");
        assertThat(again.status()).as("the month is still taken").isEqualTo(409);
        assertThat(listedFills(plants.head1().browser(), "")).containsExactly(fill);
    }

    @Test
    void anEarlierMonthMayBeStartedAndAMonthThatHasNotBegunMayNot() {
        Plants plants = published();

        startedMonth(plants.head1().browser(), plants.template(), "PLANT1", "2020-03");
        Response future = startMonth(plants.head1().browser(), plants.template(),
                "{\"plant\":\"PLANT1\",\"period\":\"2999-12\"}");
        Response notAMonth = startMonth(plants.head1().browser(), plants.template(),
                "{\"plant\":\"PLANT1\",\"period\":\"2026-13\"}");

        assertThat(future.status()).as(future.body()).isEqualTo(400);
        assertThat(future.body()).contains("period");
        assertThat(notAMonth.status()).as(notAMonth.body()).isEqualTo(400);
        assertThat(scalar(plants.world().setting().organization(), "select count(*) from inspection_fill_period "
                + "where template_id = ?", UUID.fromString(plants.template()))).isEqualTo(1);
    }

    @Test
    void aPlantThatIsNotTheirsOrIsNotPublishedToIsRefused() {
        Plants plants = plants();
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()))).status()).isEqualTo(200);

        // Plant head 2 reads nothing of a document that is published to PLANT1 only, and cannot start it.
        Response options = plants.head2().browser().get(TEMPLATES + "/" + plants.template() + "/fill-in-options");
        assertThat(options.status()).as(options.body()).isEqualTo(404);
        assertThat(startMonth(plants.head2().browser(), plants.template(), "{\"plant\":\"PLANT2\"}").status())
                .isEqualTo(404);
        // Plant head 1 names a plant they are not head of.
        Response foreign = startMonth(plants.head1().browser(), plants.template(), "{\"plant\":\"PLANT2\"}");
        assertThat(foreign.status()).as(foreign.body()).isEqualTo(400);
        assertThat(foreign.body()).contains("plant");
        assertThat(scalar(plants.world().setting().organization(), "select count(*) from inspection_fill_period"))
                .isZero();
    }

    @Test
    void thePlantMatesReadTheFillInAndCannotChangeItAndAnotherPlantSeesNothingOfIt() {
        Plants plants = published();
        String fill = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", "2026-01");

        Response mate = fillView(plants.head1b().browser(), fill);
        assertThat(mate.status()).as("a plant mate reads it").isEqualTo(200);
        assertThat(saveCells(plants.head1b().browser(), fill, fillVersion(mate), complete()).status())
                .as("only the person who started it changes it").isEqualTo(403);
        assertThat(listedFills(plants.head1b().browser(), "")).containsExactly(fill);

        assertThat(fillView(plants.head2().browser(), fill).status()).isEqualTo(404);
        assertThat(listedFills(plants.head2().browser(), "")).isEmpty();
        assertThat(fillView(plants.reviewerA().browser(), fill).status()).as("not asked yet").isEqualTo(404);
    }

    @Test
    void twoPlantHeadsStartingTheSameMonthTogetherMakeOneFillIn() throws Exception {
        Plants plants = published();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Response> first = () -> {
                go.await();
                return startMonth(plants.head1().browser(), plants.template(),
                        "{\"plant\":\"PLANT1\",\"period\":\"2026-04\"}");
            };
            Callable<Response> second = () -> {
                go.await();
                return startMonth(plants.head1b().browser(), plants.template(),
                        "{\"plant\":\"PLANT1\",\"period\":\"2026-04\"}");
            };
            Future<Response> a = pool.submit(first);
            Future<Response> b = pool.submit(second);
            go.countDown();
            List<Integer> answers = List.of(a.get().status(), b.get().status());

            assertThat(answers).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scalar(plants.world().setting().organization(), "select count(*) from inspection_fill_period "
                + "where template_id = ?", UUID.fromString(plants.template()))).isEqualTo(1);
        assertThat(listedFills(plants.world().approver().browser(), "")).hasSize(1);
    }

}
