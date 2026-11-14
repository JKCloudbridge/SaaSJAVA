package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The creator of a document publishes it to the plants and chooses the reviewers of each (MC2A, ADR-0260), on a real
 * PostgreSQL and the real application: who may do it (the creator, a person holding the ability, nobody else; every
 * endpoint allowed, denied, cross-tenant and not signed in), what a plant head of each plant can read afterwards,
 * what a plant with no group or a reviewer who is not a member does (refused before anything is shared), what a
 * change does, and what Document Management's unpublish and retire leave alone.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class PlantPublishingIT extends PlantsSupport {

    // ---- the act ----

    @Test
    void theCreatorPublishesToTwoPlantsWithDifferentReviewersAndEachPlantsHeadsCanReadIt() {
        Plants plants = plants();
        Response before = plantState(plants.creator(), plants.template());
        assertThat(before.status()).as(before.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(before.body(), "$.data.available")).isTrue();
        assertThat(JsonPath.<Boolean>read(before.body(), "$.data.canPublish")).isTrue();
        assertThat(plantCodes(before)).isEmpty();
        assertThat(reads(plants.head1().browser(), plants.template())).as("not yet").isFalse();

        Response made = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()), reviewerUser(plants.reviewerB())),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup())));

        assertThat(made.status()).as(made.body()).isEqualTo(200);
        assertThat(plantCodes(made)).containsExactly("PLANT1", "PLANT2");
        assertThat(JsonPath.<List<Integer>>read(made.body(), "$.data.plants[*].reviewers.length()"))
                .containsExactly(2, 1);
        assertThat(reads(plants.head1().browser(), plants.template())).isTrue();
        assertThat(reads(plants.head1b().browser(), plants.template())).as("a second head of the plant").isTrue();
        assertThat(reads(plants.head2().browser(), plants.template())).isTrue();
        assertThat(reads(plants.reviewerA().browser(), plants.template())).as("a reviewer reads nothing by it")
                .isFalse();
        assertThat(livePlants(plants)).isEqualTo(2);
        assertThat(liveReviewers(plants)).isEqualTo(3);
        assertThat(audited(plants.world().setting().organization(), "inspection.template.plants.published"))
                .isEqualTo(1);
        // Each plant head is told, apart from whoever acted; the notice carries facts only.
        assertThat(scalar(plants.world().setting().organization(),
                "select count(*) from notification where kind = 'DOCUMENT_AT_PLANT'")).isEqualTo(3);
    }

    @Test
    void thePeopleOfAPlantAreToldOnceAndAgainOnlyWhenThePlantIsGivenTheDocumentAgain() {
        Plants plants = plants();
        String reviewers = reviewerUser(plants.reviewerA());

        assertThat(publishToPlants(plants.creator(), plants.template(), plant("PLANT1", reviewers)).status())
                .isEqualTo(200);
        assertThat(publishToPlants(plants.creator(), plants.template(), plant("PLANT1", reviewers)).status())
                .isEqualTo(200);
        long told = scalar(plants.world().setting().organization(),
                "select count(*) from notification where kind = 'DOCUMENT_AT_PLANT'");
        assertThat(told).as("the same act again tells nobody twice").isEqualTo(2);

        assertThat(publishToPlants(plants.creator(), plants.template()).status()).isEqualTo(200);
        assertThat(publishToPlants(plants.creator(), plants.template(), plant("PLANT1", reviewers)).status())
                .isEqualTo(200);
        assertThat(scalar(plants.world().setting().organization(),
                "select count(*) from notification where kind = 'DOCUMENT_AT_PLANT'"))
                .as("a plant taken back and given again is told again").isEqualTo(4);
    }

    // ---- who may ----

    @Test
    void onlyTheCreatorAndAPersonHoldingTheAbilityMayPublishToPlants() {
        Plants plants = plants();
        String body = plant("PLANT1", reviewerUser(plants.reviewerA()));

        // The publisher of Document Management sees the document and is not its creator: no.
        Response publisher = publishToPlants(plants.world().approver().browser(), plants.template(), body);
        assertThat(publisher.status()).as(publisher.body()).isEqualTo(403);
        Response state = plantState(plants.world().approver().browser(), plants.template());
        assertThat(JsonPath.<Boolean>read(state.body(), "$.data.available")).isTrue();
        assertThat(JsonPath.<Boolean>read(state.body(), "$.data.canPublish")).isFalse();
        assertThat(plantCodes(state)).as("the reviewers are told only to who may publish").isEmpty();
        assertThat(livePlants(plants)).isZero();

        // A person who sees every document and holds the ability (the Organization administrator holds every
        // ability) may act for a document they did not make.
        var setting = plants.world().setting();
        var observer = holding(setting, "Observer");
        assertThat(publishToPlants(observer.browser(), plants.template(), body).status()).isEqualTo(403);
        String policy = JsonPath.read(setting.admin().postJson(POLICIES, "{\"name\":\"plants-" + newName()
                + "\",\"description\":\"\",\"abilities\":[\"sheets.publish-plants\"]}").body(), "$.data.id");
        give(setting.admin(), observer.member(), policy);
        Response acting = publishToPlants(observer.browser(), plants.template(), body);
        assertThat(acting.status()).as(acting.body()).isEqualTo(200);
        assertThat(livePlants(plants)).isEqualTo(1);

        // Somebody who cannot see the document learns nothing: not found, as for an unknown document.
        Response stranger = publishToPlants(plants.reviewerA().browser(), plants.template(), body);
        Response unknown = publishToPlants(plants.reviewerA().browser(), UUID.randomUUID().toString(), body);
        assertThat(stranger.status()).isEqualTo(404);
        assertThat(message(stranger)).isEqualTo(message(unknown));
    }

    @Test
    void everyEndpointIsDecidedAgainForAnotherOrganizationAndForNobodySignedIn() {
        Plants plants = plants();
        Plants other = plants();
        String body = plant("PLANT1", reviewerUser(plants.reviewerA()));
        String path = TEMPLATES + "/" + plants.template() + "/plants";

        for (Response response : List.of(
                other.creator().get(path),
                other.creator().get(path + "/options"),
                other.creator().postJson(path + "/reviewer-choices", "{\"text\":\"ab\"}"),
                publishToPlants(other.creator(), plants.template(), body))) {
            assertThat(response.status()).as(response.body()).isEqualTo(404);
        }
        TestBrowser nobody = new TestBrowser(port, plants.world().setting().organization().host());
        assertThat(nobody.get(path).status()).isEqualTo(401);
        assertThat(livePlants(plants)).isZero();
    }

    @Test
    void aDocumentWithNoRevisionInUseAndARetiredOneAreNotPublishedToPlants() {
        Plants plants = plants();
        String body = plant("PLANT1", reviewerUser(plants.reviewerA()));
        String draft = template(plants.creator(), "WI", "PRD");

        Response early = plantState(plants.creator(), draft);
        assertThat(JsonPath.<Boolean>read(early.body(), "$.data.canPublish")).isFalse();
        assertThat(JsonPath.<String>read(early.body(), "$.data.reason")).contains("Revision");
        assertThat(publishToPlants(plants.creator(), draft, body).status()).isEqualTo(409);

        assertThat(retire(plants.world().approver().browser(), plants.template()).status()).isEqualTo(200);
        Response retired = publishToPlants(plants.creator(), plants.template(), body);
        assertThat(retired.status()).as(retired.body()).isEqualTo(409);
        assertThat(message(retired)).contains("retired");
    }

    @Test
    void aDocumentOfAnApplicationThatDoesNotPublishToPlantsIsRefusedInWords() {
        Plants plants = plants();
        String other = template(plants.creator(), "WI", "OTHER");

        Response state = plantState(plants.creator(), other);
        assertThat(JsonPath.<Boolean>read(state.body(), "$.data.available")).isFalse();
        Response refused = publishToPlants(plants.creator(), other, plant("PLANT1", reviewerUser(plants.reviewerA())));
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).isEqualTo("This document is not published to plants.");
    }

    // ---- what is checked before anything is shared ----

    @Test
    void aPlantWithNoGroupAndAReviewerWhoIsNotAMemberRefuseTheWholeActAndShareNothing() {
        Plants plants = plants();
        var setting = plants.world().setting();
        long before = shares(setting.organization(), plants.template());

        // HEAD has no group of plant heads (and PLANT1 has): the whole request is refused at the place of HEAD.
        Response noGroup = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA())), plant("HEAD", reviewerUser(plants.reviewerA())));
        assertThat(noGroup.status()).as(noGroup.body()).isIn(400, 422);
        assertThat(noGroup.body()).contains("plants[1]");

        Response gone = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", "{\"kind\":\"USER\",\"id\":\"" + UUID.randomUUID() + "\"}"));
        assertThat(gone.status()).as(gone.body()).isIn(400, 422);
        assertThat(gone.body()).contains("plants[0].reviewers[0]");
        Response noGroupOfReviewers = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerGroup(UUID.randomUUID().toString())));
        assertThat(noGroupOfReviewers.status()).isIn(400, 422);
        Response none = publishToPlants(plants.creator(), plants.template(), plant("PLANT1"));
        assertThat(none.status()).as("at least one reviewer for each plant").isEqualTo(400);

        assertThat(livePlants(plants)).isZero();
        assertThat(liveReviewers(plants)).isZero();
        assertThat(shares(setting.organization(), plants.template())).isEqualTo(before);
    }

    // ---- a change, a withdrawal ----

    @Test
    void changingTheReviewersAndTakingAPlantBackKeepsWhatIsStillWantedAndRemovesWhatTheActMade() {
        Plants plants = plants();
        var setting = plants.world().setting();
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()), reviewerUser(plants.reviewerB())),
                plant("PLANT2", reviewerUser(plants.reviewerA()))).status()).isEqualTo(200);
        long withBoth = shares(setting.organization(), plants.template());

        Response changed = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerB()), reviewerGroup(plants.reviewerGroup())));

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(plantCodes(changed)).containsExactly("PLANT1");
        assertThat(liveReviewers(plants)).as("two chosen now").isEqualTo(2);
        assertThat(scalar(setting.organization(), "select count(*) from inspection_plant_reviewer where template_id = ?"
                + " and revoked_at is not null", UUID.fromString(plants.template())))
                .as("the one taken off PLANT1 and the one of PLANT2")
                .isEqualTo(2);
        assertThat(reads(plants.head1().browser(), plants.template())).isTrue();
        assertThat(reads(plants.head2().browser(), plants.template())).as("PLANT2 was taken back").isFalse();
        assertThat(shares(setting.organization(), plants.template())).isEqualTo(withBoth - 1);
        // The department of fillers Document Management published to keeps its share.
        assertThat(reads(plants.world().filler().browser(), plants.template())).isTrue();

        assertThat(publishToPlants(plants.creator(), plants.template()).status()).as("an empty list takes all back")
                .isEqualTo(200);
        assertThat(livePlants(plants)).isZero();
        assertThat(liveReviewers(plants)).isZero();
        assertThat(reads(plants.head1().browser(), plants.template())).isFalse();
        assertThat(reads(plants.world().filler().browser(), plants.template())).isTrue();
    }

    // ---- the two memories apart ----

    @Test
    void unpublishingAndRetiringInDocumentManagementLeaveThePlantsAlone() {
        Plants plants = plants();
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()))).status()).isEqualTo(200);

        assertThat(unpublish(plants.world().approver().browser(), plants.template()).status()).isEqualTo(200);
        assertThat(livePlants(plants)).isEqualTo(1);
        assertThat(reads(plants.head1().browser(), plants.template())).as("the plant heads still read it").isTrue();
        assertThat(reads(plants.world().filler().browser(), plants.template()))
                .as("Document Management's own share is gone").isFalse();

        assertThat(retire(plants.world().approver().browser(), plants.template()).status()).isEqualTo(200);
        assertThat(livePlants(plants)).isEqualTo(1);
        assertThat(reads(plants.head1().browser(), plants.template())).isTrue();
    }

    @Test
    void aShareDocumentManagementStillHoldsIsNotTakenBackByTheirUnpublishWhileAPlantNeedsIt() {
        Plants plants = plants();
        var setting = plants.world().setting();
        // Document Management publishes to the very group the plant uses (a place and a department it also offers).
        assertThat(publish(plants.world().approver().browser(), plants.template(), null, "PLANT1/PLANTHEAD").status())
                .isEqualTo(200);
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()))).status()).isEqualTo(200);
        assertThat(scalar(setting.organization(), "select count(*) from inspection_plant_publication where "
                + "template_id = ? and made_share", UUID.fromString(plants.template())))
                .as("the plant did not make the share: Document Management had").isZero();

        assertThat(unpublish(plants.world().approver().browser(), plants.template()).status()).isEqualTo(200);

        assertThat(reads(plants.head1().browser(), plants.template())).as("the plant still needs the share").isTrue();
    }

    // ---- the pickers ----

    @Test
    void theReviewerPickerAnswersASearchByNameForWhoMayPublishAndForNobodyElse() {
        Plants plants = plants();
        String path = TEMPLATES + "/" + plants.template() + "/plants/reviewer-choices";

        Response found = plants.creator().postJson(path, "{\"text\":\"reviewers\"}");
        assertThat(found.status()).as(found.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(found.body(), "$.data.groups[*].kind")).contains("GROUP");
        Response asked = plants.world().approver().browser().postJson(path, "{\"text\":\"reviewers\"}");
        assertThat(asked.status()).isEqualTo(403);
        Response options = plants.creator().get(TEMPLATES + "/" + plants.template() + "/plants/options");
        assertThat(options.status()).as(options.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(options.body(), "$.data.placesOnly")).isTrue();
        assertThat(JsonPath.<String>read(options.body(), "$.data.placeDepartment")).isEqualTo(PLANT_HEADS);
        assertThat(JsonPath.<List<Boolean>>read(options.body(),
                "$.data.locations[?(@.value=='PLANT1')].departments[0].configured")).containsExactly(true);
    }
}
