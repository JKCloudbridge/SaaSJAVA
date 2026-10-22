package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Publishing, unpublishing and retiring a template (FA4, ADR-0188): the pair of location and department becomes the
 * group an administrator keeps, the publish is a read share of the header, and taking the template back removes exactly
 * the shares the publish made. Every pair is checked before anything is shared; a refusal shares nothing; a retired
 * template is kept and its number stays.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplatePublishingIT extends TemplateSupport {

    private record Run(Setting setting, Person author, Person first, Person second, Person reader, Person other,
            String process, String plantGroup, String headGroup) {
    }

    /** Two departments with groups: Plant 1 Production holds the reader, Head office HR holds another person. */
    private Run run() {
        Setting setting = setting();
        Person author = author(setting);
        Person first = approver(setting);
        Person second = approver(setting);
        Person reader = reader(setting);
        Person other = reader(setting);
        String process = process(setting.admin(), "Templates", step("Review", user(first.userId())),
                step("Approval", user(second.userId())));
        String plant = group(setting.admin(), "plant-" + System.nanoTime(), reader);
        String head = group(setting.admin(), "head-" + System.nanoTime(), other);
        department(setting.admin(), "PLANT1", "PRD", plant);
        department(setting.admin(), "HEAD", "HR", head);
        return new Run(setting, author, first, second, reader, other, process, plant, head);
    }

    /** A template that has been through both steps and waits, approved, to be published. */
    private String approved(Run run, String category, String subCategory) {
        String template = templateWithSheet(run.author().browser(), category, subCategory);
        String request = submitted(run.author().browser(), template, run.process());
        assertThat(approve(run.first().browser(), request).status()).isEqualTo(200);
        assertThat(approve(run.second().browser(), request).status()).isEqualTo(200);
        return template;
    }

    // ---- the audience ----

    @Test
    void aPublishToSeveralDepartmentsSharesWithEachOfTheirGroupsAndOnlyThem() {
        Run run = run();
        String template = approved(run, "WI", "PRD");

        Response published = publish(run.second().browser(), template, null, "PLANT1/PRD", "HEAD/HR");

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(published.body(), "$.data.audience[*].department"))
                .containsExactly("PRD", "HR");
        assertThat(listed(run.reader().browser())).contains(template);
        assertThat(listed(run.other().browser())).contains(template);
        assertThat(shares(run.setting().organization(), template)).isEqualTo(2);
        assertThat(publications(run.setting().organization(), template, true)).isEqualTo(2);
        assertThat(audited(run.setting().organization(), "inspection.template.published")).isEqualTo(1);
    }

    @Test
    void aPairWithNoGroupRefusesTheWholePublishAndSharesNothing() {
        Run run = run();
        String template = approved(run, "WI", "PRD");

        Response refused = publish(run.second().browser(), template, null, "PLANT1/PRD", "PLANT1/QLT");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(code(refused)).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields['audiences[1]']"))
                .containsExactly("No group is set up for this department yet.");
        assertThat(refused.body()).as("the words name neither the location nor the department")
                .doesNotContain("PLANT1").doesNotContain("QLT");
        assertThat(shares(run.setting().organization(), template)).isZero();
        assertThat(publications(run.setting().organization(), template, false)).isZero();
        assertThat(statuses(run.author().browser(), template)).as("the version did not become active")
                .containsExactly("APPROVED");
        assertThat(listed(run.reader().browser())).doesNotContain(template);
    }

    @Test
    void aGroupWithNoMembersAndSeveralGroupsForOnePairAreRefusedInPlainWords() {
        Run run = run();
        String template = approved(run, "WI", "PRD");
        String empty = group(run.setting().admin(), "empty-" + System.nanoTime());
        department(run.setting().admin(), "PLANT1", "MNT", empty);
        department(run.setting().admin(), "PLANT1", "STR", run.plantGroup());
        department(run.setting().admin(), "PLANT1", "STR", run.headGroup());

        Response nobody = publish(run.second().browser(), template, null, "PLANT1/MNT");
        Response twice = publish(run.second().browser(), template, null, "PLANT1/STR");

        assertThat(JsonPath.<List<String>>read(nobody.body(), "$.error.fields['audiences[0]']"))
                .containsExactly("The group set up for this department has no members yet.");
        assertThat(JsonPath.<List<String>>read(twice.body(), "$.error.fields['audiences[0]']").get(0))
                .startsWith("More than one group is set up for this department");
        assertThat(shares(run.setting().organization(), template)).isZero();
    }

    @Test
    void aRemovedGroupIsRefusedAndOneRemovedAfterwardsTakesItsSharesWithItAndSaysSo() {
        Run run = run();
        String template = approved(run, "WI", "PRD");
        String doomed = group(run.setting().admin(), "doomed-" + System.nanoTime(), run.reader());
        department(run.setting().admin(), "PLANT1", "QLT", doomed);
        assertThat(run.setting().admin().request("DELETE", GROUPS + "/" + doomed, null).status()).isEqualTo(204);

        Response gone = publish(run.second().browser(), template, null, "PLANT1/QLT");
        assertThat(JsonPath.<List<String>>read(gone.body(), "$.error.fields['audiences[0]']"))
                .containsExactly("The group set up for this department no longer exists.");

        assertThat(publish(run.second().browser(), template, null, "HEAD/HR").status()).isEqualTo(200);
        assertThat(run.setting().admin().request("DELETE", GROUPS + "/" + run.headGroup(), null).status())
                .isEqualTo(204);

        Response after = lifecycle(run.second().browser(), template);
        assertThat(JsonPath.<Boolean>read(after.body(), "$.data.audience[0].groupAvailable")).isFalse();
        assertThat(shares(run.setting().organization(), template)).as("the existing removal ended the share").isZero();
        assertThat(publications(run.setting().organization(), template, true)).as("the memory stays").isEqualTo(1);
        assertThat(listed(run.other().browser())).doesNotContain(template);
    }

    @Test
    void anEmptyListOfDepartmentsIsRefusedBeforeAnythingHappens() {
        Run run = run();
        String template = approved(run, "WI", "PRD");

        Response none = run.second().browser().postJson(TEMPLATES + "/" + template + "/publish",
                "{\"audiences\":[]}");
        Response same = publish(run.second().browser(), template, null, "PLANT1/PRD", "PLANT1/PRD");

        assertThat(none.status()).isEqualTo(400);
        assertThat(same.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(same.body(), "$.error.fields['audiences[1]']"))
                .containsExactly("This department is listed twice.");
        assertThat(shares(run.setting().organization(), template)).isZero();
    }

    // ---- taking it back ----

    @Test
    void unpublishRemovesExactlyTheSharesThePublishMadeAndLeavesOneMadeByHand() {
        Run run = run();
        String template = approved(run, "WI", "PRD");
        // The author gives the plant's group write access by hand before anything is published.
        Response byHand = run.author().browser().request("PUT", RECORDS + "/" + template + "/shares",
                "{\"granteeType\":\"GROUP\",\"granteeId\":\"" + run.plantGroup()
                        + "\",\"accessLevel\":\"READ_WRITE\"}");
        assertThat(byHand.status()).as(byHand.body()).isEqualTo(200);

        assertThat(publish(run.second().browser(), template, null, "PLANT1/PRD", "HEAD/HR").status()).isEqualTo(200);
        assertThat(shares(run.setting().organization(), template)).isEqualTo(2);
        assertThat(scalar(run.setting().organization(),
                "select count(*) from record_share where record_id = ? and access_level = 2",
                java.util.UUID.fromString(template))).as("the publish did not lower the hand-made share").isEqualTo(1);

        Response taken = unpublish(run.second().browser(), template);

        assertThat(taken.status()).as(taken.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(taken.body(), "$.data.lifecycle.state")).isEqualTo("APPROVED");
        assertThat(statuses(run.author().browser(), template)).containsExactly("APPROVED");
        assertThat(shares(run.setting().organization(), template)).as("only the hand-made share is left").isEqualTo(1);
        assertThat(listed(run.other().browser())).doesNotContain(template);
        assertThat(publications(run.setting().organization(), template, true)).isZero();
        assertThat(publications(run.setting().organization(), template, false)).isEqualTo(2);
        // The hand-made share still reaches the group, and nothing the publish did came back.
        assertThat(JsonPath.<Boolean>read(lifecycle(run.second().browser(), template).body(),
                "$.data.canUnpublish")).isFalse();
    }

    @Test
    void changingTheAudienceTakesBackWhatIsNotAskedForAndKeepsWhatIs() {
        Run run = run();
        String template = approved(run, "WI", "PRD");
        assertThat(publish(run.second().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(listed(run.reader().browser())).contains(template);

        Response changed = publish(run.second().browser(), template, null, "HEAD/HR");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(changed.body(), "$.data.audience[*].department")).containsExactly("HR");
        assertThat(listed(run.reader().browser())).doesNotContain(template);
        assertThat(listed(run.other().browser())).contains(template);
        assertThat(shares(run.setting().organization(), template)).isEqualTo(1);
        assertThat(statuses(run.author().browser(), template)).as("the version in use is the same one")
                .containsExactly("ACTIVE");
    }

    @Test
    void nothingToPublishNothingPublishedAndAVersionThatIsNotApprovedAreEachRefusedInWords() {
        Run run = run();
        String draft = templateWithSheet(run.author().browser(), "WI", "PRD");

        Response nothing = publish(run.second().browser(), draft, null, "PLANT1/PRD");
        Response notApproved = publish(run.second().browser(), draft, 1, "PLANT1/PRD");
        Response notPublished = unpublish(run.second().browser(), draft);

        assertThat(nothing.status()).isEqualTo(409);
        assertThat(message(nothing)).contains("no approved version");
        assertThat(notApproved.status()).isEqualTo(409);
        assertThat(message(notApproved)).contains("Only an approved version");
        assertThat(notPublished.status()).isEqualTo(409);
        assertThat(message(notPublished)).contains("not published");
        assertThat(statuses(run.author().browser(), draft)).containsExactly("DRAFT");
    }

    // ---- what the screen is told it may offer ----

    @Test
    void theFlagsFollowTheAbilitiesAndTheStateAndMakeNoDecision() {
        Run run = run();
        String template = approved(run, "WI", "PRD");

        Response forApprover = lifecycle(run.second().browser(), template);
        assertThat(JsonPath.<Boolean>read(forApprover.body(), "$.data.canPublish")).isTrue();
        assertThat(JsonPath.<Boolean>read(forApprover.body(), "$.data.canUnpublish")).isFalse();
        assertThat(JsonPath.<Boolean>read(forApprover.body(), "$.data.canRetire")).isTrue();
        Response forAuthor = lifecycle(run.author().browser(), template);
        assertThat(JsonPath.<Boolean>read(forAuthor.body(), "$.data.canPublish")).isFalse();
        assertThat(JsonPath.<Boolean>read(forAuthor.body(), "$.data.canRetire")).isFalse();

        // A flag is not a permission: the author who calls it anyway is refused.
        assertThat(publish(run.author().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(403);
        assertThat(retire(run.author().browser(), template).status()).isEqualTo(403);
        assertThat(unpublish(run.author().browser(), template).status()).isEqualTo(403);
        assertThat(statuses(run.author().browser(), template)).containsExactly("APPROVED");
    }

    @Test
    void thePublishDialogOffersEveryLocationWithItsDepartmentsAndWhetherAGroupIsSetUp() {
        Run run = run();

        Response options = run.second().browser().get(TEMPLATES + "/audience-options");

        assertThat(options.status()).as(options.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(options.body(), "$.data.locations[*].value"))
                .containsExactly("PLANT1", "PLANT2", "HEAD");
        assertThat(JsonPath.<List<String>>read(options.body(), "$.data.locations[0].departments[*].value"))
                .containsExactly("PRD", "QLT", "MNT", "STR");
        assertThat(JsonPath.<List<Boolean>>read(options.body(), "$.data.locations[0].departments[*].configured"))
                .containsExactly(true, false, false, false);
        assertThat(JsonPath.<List<String>>read(options.body(), "$.data.locations[2].departments[*].value"))
                .containsExactly("HR", "FIN");
        assertThat(run.author().browser().get(TEMPLATES + "/audience-options").status()).as("needs the ability")
                .isEqualTo(403);
    }

    // ---- retiring ----

    @Test
    void retireKeepsEverythingEndsTheVersionInUseAndNeverGivesTheNumberAgain() {
        Run run = run();
        String template = approved(run, "WI", "PRD");
        assertThat(publish(run.second().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);

        Response retired = retire(run.second().browser(), template);

        assertThat(retired.status()).as(retired.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(retired.body(), "$.data.lifecycle.state")).isEqualTo("RETIRED");
        assertThat(field(run.setting().admin(), template, "retired")).isEqualTo(true);
        assertThat(statuses(run.author().browser(), template)).containsExactly("RETIRED");
        assertThat(number(run.setting().admin(), template)).isEqualTo("WI/PRD/01");
        // The people who had it still read it; nothing was deleted; a second retire is refused.
        assertThat(version(run.reader().browser(), template, 1).status()).isEqualTo(200);
        assertThat(shares(run.setting().organization(), template)).isEqualTo(1);
        assertThat(retire(run.second().browser(), template).status()).isEqualTo(409);
        assertThat(publish(run.second().browser(), template, 1, "PLANT1/PRD").status()).isEqualTo(409);
        // A retired template cannot be sent for approval again, and the next one is numbered after it.
        int next = newVersion(run.author().browser(), template);
        fill(run.author().browser(), template, next);
        Response again = submit(run.author().browser(), template, run.process());
        assertThat(again.status()).isEqualTo(409);
        assertThat(message(again)).contains("retired");
        String other = approved(run, "WI", "PRD");
        assertThat(number(run.setting().admin(), other)).isEqualTo("WI/PRD/02");
    }

    @Test
    void retireIsRefusedWhileAVersionIsWaitingForApprovalAndTheTemplateIsLeftAsItWas() {
        Run run = run();
        String template = approved(run, "WI", "PRD");
        publish(run.second().browser(), template, null, "PLANT1/PRD");
        int two = newVersion(run.author().browser(), template);
        fill(run.author().browser(), template, two);
        String request = submitted(run.author().browser(), template, run.process());

        Response refused = retire(run.second().browser(), template);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(message(refused)).contains("waiting for approval");
        assertThat(field(run.setting().admin(), template, "retired")).isNull();
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW", "ACTIVE");
        assertThat(recall(run.author().browser(), request).status()).isEqualTo(200);
        assertThat(retire(run.second().browser(), template).status()).isEqualTo(200);
    }

    @Test
    void nobodyCanMarkATemplateRetiredOrChangeItsNumberByHand() {
        Run run = run();
        String template = approved(run, "WI", "PRD");

        Response retiredByHand = run.setting().admin().request("PATCH", RECORDS + "/" + template,
                "{\"fields\":{\"retired\":true},\"version\":" + JsonPath.<Number>read(
                        run.setting().admin().get(RECORDS + "/" + template).body(), "$.data.version") + "}");
        Response numberByHand = run.author().browser().request("PATCH", RECORDS + "/" + template,
                "{\"fields\":{\"number\":\"X/1\"},\"version\":" + JsonPath.<Number>read(
                        run.author().browser().get(RECORDS + "/" + template).body(), "$.data.version") + "}");

        assertThat(retiredByHand.status()).as("not even the administrator: " + retiredByHand.body()).isEqualTo(400);
        assertThat(numberByHand.status()).isIn(400, 403);
        assertThat(field(run.setting().admin(), template, "retired")).isNull();
        assertThat(number(run.setting().admin(), template)).isEqualTo("WI/PRD/01");
    }

    // ---- two at once ----

    @Test
    void twoApproversPublishingTheSameTemplateAtOnceLeaveOnePublicationAndOneShare() {
        Run run = run();
        String template = approved(run, "WI", "PRD");
        TestBrowser a = run.first().browser();
        TestBrowser b = run.second().browser();

        CompletableFuture<Response> one = CompletableFuture.supplyAsync(() -> publish(a, template, null, "PLANT1/PRD"));
        CompletableFuture<Response> two = CompletableFuture.supplyAsync(() -> publish(b, template, null, "PLANT1/PRD"));

        assertThat(List.of(one.join().status(), two.join().status())).as("the second finds it done and changes nothing")
                .containsOnly(200);
        assertThat(publications(run.setting().organization(), template, true)).isEqualTo(1);
        assertThat(shares(run.setting().organization(), template)).isEqualTo(1);
        assertThat(statuses(run.author().browser(), template)).containsExactly("ACTIVE");
    }
}
