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
 * through the list, the inbox or the fill-in itself, a change of reviewers reaches a fill-in already waiting (since
 * 2026-11-17: the person taken off loses the task, the person added gets it and can open the fill-in), a
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

    /** A licensed person who holds the shipped Reader policy, as a reviewer of a plant is meant to (2026-11-17). */
    private Person reader(Plants plants) {
        var setting = plants.world().setting();
        Person person = holding(setting, "Reader");
        app.platform.testsupport.TestMembers.giveLicence(setting.organization().id(), person.member().membership(),
                "user");
        return person;
    }

    @Test
    void aChangeOfReviewersReachesAFillInThatIsWaitingAndLeavesAnotherPlantAlone() {
        Plants plants = published();
        Person added = reader(plants);
        String request = sendMonth(plants, plants.head1(), "PLANT1", "2026-01");
        String other = sendMonth(plants, plants.head2(), "PLANT2", "2026-01");
        String fill = fillOf(plants.head1());

        // PLANT1: A is taken off, B is kept, a new reviewer is added; PLANT2 keeps its group.
        Response changed = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerB()), reviewerUser(added)),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup())));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);

        assertThat(tasks(plants.reviewerA().browser())).as("taken off: the task is closed").doesNotContain(request);
        assertThat(approve(plants.reviewerA().browser(), request).status()).isGreaterThanOrEqualTo(400);
        assertThat(fillView(plants.reviewerA().browser(), fill).status()).as("no task, no right: nothing to read")
                .isEqualTo(404);
        assertThat(tasks(plants.reviewerB().browser())).as("kept").containsExactly(request);
        assertThat(tasks(added.browser())).as("added: asked now").containsExactly(request);
        assertThat(tasks(plants.reviewerC().browser())).as("another plant is untouched").containsExactly(other);

        // The reviewer added now opens the fill-in itself (a read share and the Reader policy) and decides it.
        assertThat(fillView(added.browser(), fill).status()).isEqualTo(200);
        assertThat(headerStatus(added.browser(), fill)).isEqualTo("SUBMITTED");
        assertThat(approve(added.browser(), request).status()).isEqualTo(200);
        assertThat(tasks(plants.reviewerB().browser())).as("the step is decided").doesNotContain(request);
        assertThat(tasks(plants.world().approver().browser())).as("the next step of this process").contains(request);

        // The history says who was asked and who was left out, and why: one line each.
        assertThat(scalar(plants.world().setting().organization(), "select count(*) from approval_history "
                + "where instance_id = ? and reason = 'reviewers_changed'", java.util.UUID.fromString(request)))
                .isEqualTo(2);
    }

    private static long waitingForAnAdministrator(Plants plants, String request) {
        return scalar(plants.world().setting().organization(), "select count(*) from approval_instance "
                + "where id = ? and attention = 'no_approver'", java.util.UUID.fromString(request));
    }

    @Test
    void whenTheChangeLeavesNobodyToAskTheWaitingFillInAsksForAnAdministratorAndAnAdditionClearsIt() {
        Plants plants = published();
        Person unlicensed = outsider(plants.world().setting());
        String request = sendMonth(plants, plants.head1(), "PLANT1", "2026-01");
        assertThat(waitingForAnAdministrator(plants, request)).isZero();

        assertThat(publishToPlants(plants.creator(), plants.template(), plant("PLANT1", reviewerUser(unlicensed)),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup()))).status()).isEqualTo(200);

        assertThat(waitingForAnAdministrator(plants, request)).as("nobody can be asked: an administrator").isOne();
        assertThat(tasks(plants.reviewerA().browser())).doesNotContain(request);
        assertThat(tasks(plants.reviewerB().browser())).doesNotContain(request);
        assertThat(tasks(unlicensed.browser())).as("no licence, not asked").isEmpty();

        // Choosing a reviewer again gives the task back and the request no longer waits for an administrator.
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA())),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup()))).status()).isEqualTo(200);
        assertThat(tasks(plants.reviewerA().browser())).containsExactly(request);
        assertThat(waitingForAnAdministrator(plants, request)).isZero();
        assertThat(approve(plants.reviewerA().browser(), request).status()).isEqualTo(200);
    }

    @Test
    void aReviewerWhoHoldsTheReaderPolicyOpensTheFillInTheyAreAskedAboutAndNoOther() {
        Plants plants = plants();
        Person reviewer = reader(plants);
        assertThat(publishToPlants(plants.creator(), plants.template(), plant("PLANT1", reviewerUser(reviewer)),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup()))).status()).isEqualTo(200);
        String request = sendMonth(plants, plants.head1(), "PLANT1", "2026-01");
        sendMonth(plants, plants.head2(), "PLANT2", "2026-01");
        String mine = fillOf(plants.head1());
        String theirs = fillOf(plants.head2());

        assertThat(tasks(reviewer.browser())).containsExactly(request);
        assertThat(headerStatus(reviewer.browser(), mine)).as("the header, shared with the reviewer")
                .isEqualTo("SUBMITTED");
        assertThat(reviewer.browser().get(FILL_RECORDS + "/" + theirs).status()).as("another plant's")
                .isEqualTo(404);
        Response inbox = reviewer.browser().get(INBOX);
        assertThat(com.jayway.jsonpath.JsonPath.<List<Boolean>>read(inbox.body(), "$.data[*].recordReadable"))
                .as("the inbox opens it").containsExactly(true);
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
