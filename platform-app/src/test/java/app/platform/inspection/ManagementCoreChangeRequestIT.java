package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Change requests of a document that belongs to Management Core (MC2B, ADR-0261), through the real application: a
 * management user who can read the published document asks for one and the request carries the application; a plant
 * head of one of its plants, who reads it only through the plant, is told that management users ask; a person who
 * cannot read the document asks nothing and learns nothing; and the Revision that the request ends in keeps the plants
 * and the reviewers chosen for them (ADR-0260).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ManagementCoreChangeRequestIT extends PlantsSupport {

    private static String asking(String template) {
        return "{\"versionNo\":1,\"register\":{\"requestNo\":\"DCN-1\",\"changeRequested\":\"A new revision\"},"
                + "\"comments\":[]}";
    }

    private Plants published() {
        Plants plants = plants();
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA())),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup()))).status()).isEqualTo(200);
        return plants;
    }

    @Test
    void aManagementUserWhoReadsTheDocumentAsksAndTheRequestCarriesTheApplication() {
        Plants plants = published();
        ChangeRequestSupport.reviewProcess(plants.world().setting().admin(), "Changes" + System.nanoTime(),
                step("Decide", user(plants.world().approver().userId())));
        var asker = plants.world().filler().browser();
        assertThat(reads(asker, plants.template())).as("a person of the department it is published to").isTrue();

        Response made = asker.postJson(ChangeRequestSupport.changes(plants.template()), asking(plants.template()));
        assertThat(made.status()).as(made.body()).isEqualTo(201);

        String review = ChangeRequestSupport.reviewIdOf(made);
        Response record = plants.world().approver().browser().get(ChangeRequestSupport.REVIEW_RECORDS + "/" + review);
        assertThat(record.status()).as(record.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(record.body(), "$.data.fields.application")).isEqualTo(CORE);
    }

    @Test
    void aPlantHeadWhoReadsItOnlyThroughThePlantIsToldThatManagementUsersAsk() {
        Plants plants = published();
        assertThat(reads(plants.head1().browser(), plants.template())).isTrue();

        Response refused = plants.head1().browser().postJson(ChangeRequestSupport.changes(plants.template()),
                asking(plants.template()));

        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        assertThat(message(refused)).contains("management users");
        assertThat(scalar(plants.world().setting().organization(),
                "select count(*) from inspection_change_request")).isZero();
    }

    @Test
    void aPersonWhoCannotReadTheDocumentCannotAskAboutItAndLearnsNothing() {
        Plants plants = published();
        Response refused = plants.world().outsider().browser().postJson(
                ChangeRequestSupport.changes(plants.template()), asking(plants.template()));

        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void theRevisionARequestEndsInKeepsThePlantsAndTheirReviewers() {
        Plants plants = published();
        long plantsBefore = livePlants(plants);
        long reviewersBefore = liveReviewers(plants);

        NextRevision.inUse(plants.world().setting(), plants.creator(), plants.world().approver(), plants.template(),
                sheet(2, "FIXED_AND_REPEATABLE").replace("Finding", "Observation"));

        assertThat(livePlants(plants)).isEqualTo(plantsBefore).isEqualTo(2);
        assertThat(liveReviewers(plants)).isEqualTo(reviewersBefore).isEqualTo(2);
        assertThat(plantCodes(plantState(plants.creator(), plants.template()))).containsExactlyInAnyOrder("PLANT1",
                "PLANT2");
        assertThat(reads(plants.head1().browser(), plants.template())).as("the plant head still reads it").isTrue();
    }
}
