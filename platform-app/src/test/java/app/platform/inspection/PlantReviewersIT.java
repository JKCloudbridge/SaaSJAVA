package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The reviewers chosen for a plant review its fill-ins and nobody else's (MC2A, ADR-0260), on a real PostgreSQL and the
 * real application: the approver rule {@code PUBLICATION_REVIEWERS} is resolved when the step becomes active, a person
 * and a group are both read (the group at that moment), a reviewer of another plant is asked nothing and reads nothing
 * through the list, the inbox or the fill-in itself, a change of reviewers never reaches a request already running, a
 * plant with nobody to ask refuses the send in the engine's words, an unlicensed reviewer is left out and a reviewer is
 * never asked to decide the fill-in they sent themselves.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class PlantReviewersIT extends PlantsSupport {

    private Plants published() {
        Plants plants = plants();
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()), reviewerUser(plants.reviewerB())),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup()))).status()).isEqualTo(200);
        return plants;
    }

    /** A plant head starts the month, fills it completely and sends it; returns the request. */
    private String sendMonth(Plants plants, Person head, String plant, String period) {
        String fill = startedMonth(head.browser(), plants.template(), plant, period);
        saved(head.browser(), fill, complete());
        return sent(head.browser(), fill, plants.process());
    }

    private String fillOf(Person head) {
        return listedFills(head.browser(), "").get(0);
    }

    @Test
    void eachPlantsFillInIsAskedOfItsOwnReviewersAndOfNoOthersAndTheChainGoesOnToTheLastStep() {
        Plants plants = published();
        String one = sendMonth(plants, plants.head1(), "PLANT1", "2026-01");
        String two = sendMonth(plants, plants.head2(), "PLANT2", "2026-01");

        assertThat(tasks(plants.reviewerA().browser())).containsExactly(one);
        assertThat(tasks(plants.reviewerB().browser())).containsExactly(one);
        assertThat(tasks(plants.reviewerC().browser())).as("through the group of PLANT2").containsExactly(two);
        assertThat(tasks(plants.world().approver().browser())).as("the last step is not active yet").isEmpty();

        assertThat(approve(plants.reviewerA().browser(), one).status()).isEqualTo(200);
        assertThat(tasks(plants.world().approver().browser())).containsExactly(one);
        assertThat(approve(plants.world().approver().browser(), one).status()).isEqualTo(200);
        assertThat(fillStatus(plants.head1().browser(), fillOf(plants.head1()))).isEqualTo("APPROVED");
        assertThat(fillStatus(plants.head2().browser(), fillOf(plants.head2()))).as("the other plant's is untouched")
                .isEqualTo("SUBMITTED");
    }

    @Test
    void aReviewerOfAnotherPlantSeesAndDecidesNothingOfAFillInThroughAnyDoor() {
        Plants plants = published();
        String request = sendMonth(plants, plants.head1(), "PLANT1", "2026-01");
        String fill = fillOf(plants.head1());
        var other = plants.reviewerC().browser();

        assertThat(tasks(other)).doesNotContain(request);
        assertThat(fillView(other, fill).status()).as("the fill-in").isEqualTo(404);
        // A person with no right on the object at all is told the object does not exist; with a right they get nothing.
        for (String query : List.of("", "?status=SUBMITTED")) {
            Response listed = other.get(FILL_INS + query);
            assertThat(listed.status()).as("the list " + query).isIn(200, 404);
            assertThat(listed.body()).doesNotContain(fill);
        }
        assertThat(headerStatusOrNull(other, fill)).as("the header record").isNull();
        assertThat(other.get(INSTANCES + "/" + request).status()).as("the request itself").isEqualTo(404);
        assertThat(fillView(plants.head2().browser(), fill).status()).as("a plant head of another plant")
                .isEqualTo(404);
        // The one who is asked reads that one fill-in.
        assertThat(fillView(plants.reviewerA().browser(), fill).status()).isEqualTo(200);
    }

    private static String headerStatusOrNull(app.platform.testsupport.TestBrowser browser, String fill) {
        Response header = browser.get(FILL_RECORDS + "/" + fill);
        return header.status() == 200 ? headerStatus(browser, fill) : null;
    }

    @Test
    void aGroupIsExpandedWhenTheStepBecomesActiveAndAPersonWhoJoinsLaterIsNotAsked() {
        Plants plants = published();
        var setting = plants.world().setting();
        String request = sendMonth(plants, plants.head2(), "PLANT2", "2026-01");
        Person late = licensed(setting);
        Response added = setting.admin().postJson(GROUPS + "/" + plants.reviewerGroup() + "/members",
                "{\"membershipId\":\"" + late.membership() + "\"}");
        assertThat(added.status()).as(added.body()).isIn(200, 201, 204);

        assertThat(tasks(plants.reviewerC().browser())).containsExactly(request);
        assertThat(tasks(late.browser())).as("added after the step became active").isEmpty();
        // The next month's fill-in is asked of the group as it is then.
        String next = sendMonth(plants, plants.head2(), "PLANT2", "2026-02");
        assertThat(tasks(late.browser())).containsExactly(next);
    }

    @Test
    void aChangeOfReviewersNeverReachesARequestThatIsRunning() {
        Plants plants = published();
        String request = sendMonth(plants, plants.head1(), "PLANT1", "2026-01");

        Response changed = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerB())), plant("PLANT2", reviewerUser(plants.reviewerC())));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);

        assertThat(tasks(plants.reviewerA().browser())).as("kept: the task was made then").containsExactly(request);
        assertThat(approve(plants.reviewerA().browser(), request).status()).isEqualTo(200);
        String later = sendMonth(plants, plants.head1(), "PLANT1", "2026-02");
        assertThat(tasks(plants.reviewerA().browser())).as("not asked of the next one").doesNotContain(later);
        assertThat(tasks(plants.reviewerB().browser())).contains(later);
    }

    @Test
    void aPlantWithNobodyToAskRefusesTheSendInTheEnginesWordsAndTheFillInStaysADraft() {
        Plants plants = published();
        String fill = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", "2026-01");
        saved(plants.head1().browser(), fill, complete());
        // The creator takes the plant back: its reviewers end with it, and the draft cannot be sent until the plant
        // is given the document again.
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT2", reviewerUser(plants.reviewerC()))).status()).isEqualTo(200);

        Response refused = sendFill(plants.head1().browser(), fill, plants.process());

        assertThat(refused.status()).as(refused.body()).isBetween(400, 499);
        assertThat(fillStatus(plants.head1().browser(), fill)).isEqualTo("DRAFT");
        assertThat(tasks(plants.reviewerA().browser())).isEmpty();
    }

    @Test
    void aReviewerWithNoLicenceIsLeftOutAndIfNobodyIsLeftTheSendIsRefused() {
        Plants plants = plants();
        var setting = plants.world().setting();
        Person unlicensed = outsider(setting);
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(unlicensed)),
                plant("PLANT2", reviewerUser(unlicensed), reviewerUser(plants.reviewerC()))).status()).isEqualTo(200);

        String fillOne = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", "2026-01");
        saved(plants.head1().browser(), fillOne, complete());
        assertThat(sendFill(plants.head1().browser(), fillOne, plants.process()).status()).isBetween(400, 499);

        String two = sendMonth(plants, plants.head2(), "PLANT2", "2026-01");
        assertThat(tasks(unlicensed.browser())).isEmpty();
        assertThat(tasks(plants.reviewerC().browser())).containsExactly(two);
    }

    @Test
    void aReviewerWhoIsAlsoThePlantHeadWhoSentTheFillInIsNotAskedToDecideIt() {
        Plants plants = plants();
        var setting = plants.world().setting();
        app.platform.testsupport.TestMembers.giveLicence(setting.organization().id(),
                plants.head1().member().membership(), "user");
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.head1()), reviewerUser(plants.reviewerA()))).status())
                .isEqualTo(200);

        String request = sendMonth(plants, plants.head1(), "PLANT1", "2026-01");

        assertThat(tasks(plants.head1().browser())).as("the engine leaves the person who sent it out").isEmpty();
        assertThat(tasks(plants.reviewerA().browser())).containsExactly(request);
        assertThat(List.of(approve(plants.head1().browser(), request).status())).allMatch(status -> status >= 400);
    }
}
