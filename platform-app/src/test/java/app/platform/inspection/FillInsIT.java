package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The fill-ins of the Audit maintenance application, end to end (Functional track FF1, ADR-0192): started on the
 * version
 * in use and pinned to it, filled in by input cells only with each column type enforced, rows added by the filler
 * living
 * in the fill-in, saved half done and sent complete, reviewed by the approval engine (approved, returned, taken back),
 * corrected after it was sent only by the people who may, and listed newest first by key without the cells. Two
 * fill-ins
 * of one template never change each other or the template. The words of the solution are in these tests because they
 * test
 * the solution that ships.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class FillInsIT extends FillSupport {

    // ---- start, and the pin to the version (criteria 1 and 6) ----

    @Test
    void aFillInStartsOnTheVersionInUseAndShowsAgainstItAfterTheTemplateMovesOn() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());

        Response first = fillView(world.filler().browser(), fill);
        assertThat(JsonPath.<Integer>read(first.body(), "$.data.fill.versionNo")).isEqualTo(1);
        assertThat(JsonPath.<String>read(first.body(), "$.data.fill.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<Boolean>read(first.body(), "$.data.canEdit")).isTrue();
        assertThat(JsonPath.<Boolean>read(first.body(), "$.data.correction")).isFalse();
        assertThat(JsonPath.<String>read(first.body(), "$.data.grid.columns[0].label")).isEqualTo("Finding");
        assertThat(JsonPath.<List<Object>>read(first.body(), "$.data.cells.rows")).isEmpty();

        int second = nextVersionInUse(world);

        Response after = fillView(world.filler().browser(), fill);
        assertThat(JsonPath.<Integer>read(after.body(), "$.data.fill.versionNo")).as("pinned for ever").isEqualTo(1);
        assertThat(JsonPath.<String>read(after.body(), "$.data.fill.versionStatus")).isEqualTo("SUPERSEDED");
        assertThat(JsonPath.<String>read(after.body(), "$.data.grid.columns[0].label")).as("its own structure")
                .isEqualTo("Finding");
        String next = started(world.colleague().browser(), world.template());
        Response onNew = fillView(world.colleague().browser(), next);
        assertThat(JsonPath.<Integer>read(onNew.body(), "$.data.fill.versionNo")).isEqualTo(second);
        assertThat(JsonPath.<String>read(onNew.body(), "$.data.grid.columns[0].label")).isEqualTo("Observation");
    }

    @Test
    void nothingIsStartedWhereNoVersionIsInUse() {
        World world = world();
        Person both = approver(world.setting());
        give(world.setting().admin(), both.member(), policyId(world.setting().admin(), "Filler"));
        assertThat(unpublish(world.approver().browser(), world.template()).status()).isEqualTo(200);

        Response options = both.browser().get(TEMPLATES + "/" + world.template() + "/fill-in-options");
        Response start = startFill(both.browser(), world.template());

        assertThat(options.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(options.body(), "$.data.canStart")).isFalse();
        assertThat(JsonPath.<Integer>read(options.body(), "$.data.activeVersion")).isZero();
        assertThat(JsonPath.<String>read(options.body(), "$.data.reason")).contains("no version in use");
        assertThat(start.status()).isEqualTo(409);
        assertThat(listedFills(both.browser(), "")).isEmpty();
    }

    @Test
    void aRetiredTemplateStartsNoNewFillIn() {
        World world = world();
        Person both = approver(world.setting());
        give(world.setting().admin(), both.member(), policyId(world.setting().admin(), "Filler"));
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);

        Response options = both.browser().get(TEMPLATES + "/" + world.template() + "/fill-in-options");

        assertThat(JsonPath.<Boolean>read(options.body(), "$.data.canStart")).isFalse();
        assertThat(JsonPath.<String>read(options.body(), "$.data.reason")).contains("retired");
        assertThat(startFill(both.browser(), world.template()).status()).isEqualTo(409);
    }

    // ---- two fill-ins never change each other or the template (criterion 3) ----

    @Test
    void twoFillInsOfOneTemplateHoldDifferentValuesAndNeverChangeEachOtherOrTheTemplate() {
        World world = world();
        Response before = version(world.author().browser(), world.template(), 1);
        String mine = started(world.filler().browser(), world.template());
        String theirs = started(world.colleague().browser(), world.template());

        saved(world.filler().browser(), mine, complete());
        saved(world.colleague().browser(), theirs, cells(row("r1", "\"t\":\"Other\"")));

        Response mineNow = fillView(world.filler().browser(), mine);
        Response theirsNow = fillView(world.colleague().browser(), theirs);
        assertThat(JsonPath.<String>read(mineNow.body(), "$.data.cells.rows[0].cells.t")).isEqualTo("Door open");
        assertThat(JsonPath.<List<Object>>read(theirsNow.body(), "$.data.cells.rows")).hasSize(1);
        assertThat(JsonPath.<String>read(theirsNow.body(), "$.data.cells.rows[0].cells.t")).isEqualTo("Other");
        assertThat(fillView(world.filler().browser(), theirs).status()).as("not even to look").isEqualTo(404);
        Response after = version(world.author().browser(), world.template(), 1);
        assertThat(JsonPath.<String>read(after.body(), "$.data.version.gridHash"))
                .as("the template and its version are untouched")
                .isEqualTo(JsonPath.<String>read(before.body(), "$.data.version.gridHash"));
        assertThat(JsonPath.<Integer>read(after.body(), "$.data.version.version"))
                .isEqualTo(JsonPath.<Integer>read(before.body(), "$.data.version.version"));
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("ACTIVE");
    }

    // ---- only input cells, and each column type (criterion 10) ----

    @Test
    void aCellTheSheetFixesTakesNoValueAndTheRefusalNamesThePlaceNotTheText() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        Response view = fillView(world.filler().browser(), fill);

        Response refused = saveCells(world.filler().browser(), fill, fillVersion(view),
                cells(row("r1", "\"d\":\"2026-01-01\",\"t\":\"x\"")));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields['cells.rows[0].cells.d']"))
                .containsExactly("This cell cannot be typed into.");
        assertThat(refused.body()).doesNotContain("2026-01-01");
        assertThat(JsonPath.<List<Object>>read(fillView(world.filler().browser(), fill).body(),
                "$.data.cells.rows")).as("nothing was saved").isEmpty();
    }

    @Test
    void eachColumnTypeIsEnforcedAndAColumnOrRowThatIsNotThereIsRefused() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));

        List<String> bad = List.of("\"n\":\"12a\"", "\"n\":12", "\"d\":\"2026-02-30\"", "\"d\":\"01/02/2026\"",
                "\"p\":\"Maybe\"", "\"b\":\"yes\"", "\"t\":true", "\"zz\":\"x\"", "\"t\":\"<script>x</script>\"");
        for (String cell : bad) {
            Response refused = saveCells(world.filler().browser(), fill, version, cells(row("r2", cell)));
            assertThat(refused.status()).as(cell + ": " + refused.body()).isEqualTo(400);
        }
        Response unknownRow = saveCells(world.filler().browser(), fill, version, cells(row("r1", "\"t\":\"x\""),
                row("t", "\"t\":\"x\"")));
        assertThat(unknownRow.status()).as("an identifier of a column cannot be a row").isEqualTo(400);
        Response good = saveCells(world.filler().browser(), fill, version, cells(row("r2",
                "\"t\":\"x\",\"n\":\"-3.5\",\"d\":\"2026-02-28\",\"p\":\"Fail\",\"b\":false")));
        assertThat(good.status()).as(good.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(fillView(world.filler().browser(), fill).body(),
                "$.data.cells.rows[0].cells.b")).as("a false is a value").isFalse();
    }

    @Test
    void aSaveOfAnOldRowVersionIsAConflictAndChangesNothing() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long old = fillVersion(fillView(world.filler().browser(), fill));
        saved(world.filler().browser(), fill, cells(row("r1", "\"t\":\"first\"")));

        Response stale = saveCells(world.filler().browser(), fill, old, cells(row("r1", "\"t\":\"second\"")));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(JsonPath.<String>read(fillView(world.filler().browser(), fill).body(),
                "$.data.cells.rows[0].cells.t")).isEqualTo("first");
    }

    // ---- rows (criterion 5) ----

    @Test
    void aRowTheFillerAddsLivesInTheFillInAndNotInTheTemplate() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());

        saved(world.filler().browser(), fill, cells(row("r1", "\"t\":\"named\""), row("add1", "\"t\":\"added\""),
                row("add2", "")));

        Response view = fillView(world.filler().browser(), fill);
        assertThat(JsonPath.<Integer>read(view.body(), "$.data.fill.rowCount")).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.cells.rows[*].id"))
                .containsExactly("r1", "add1", "add2");
        Response template = version(world.author().browser(), world.template(), 1);
        assertThat(JsonPath.<List<String>>read(template.body(), "$.data.grid.rows[*].id")).containsExactly("r1", "r2");
    }

    @Test
    void whereTheRowsAreFixedNoRowCanBeAdded() {
        World world = world(2, "FIXED");
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));

        Response refused = saveCells(world.filler().browser(), fill, version, cells(row("add1", "\"t\":\"x\"")));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(saveCells(world.filler().browser(), fill, version, cells(row("r2", "\"t\":\"x\""))).status())
                .isEqualTo(200);
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsNeedsAtLeastOneToBeSent() {
        World world = world(1, "REPEATABLE");
        String fill = started(world.filler().browser(), world.template());

        Response none = sendFill(world.filler().browser(), fill, world.process());
        saved(world.filler().browser(), fill, cells(row("a", "\"t\":\"one\"")));
        Response one = sendFill(world.filler().browser(), fill, world.process());

        assertThat(none.status()).isEqualTo(409);
        assertThat(message(none)).contains("at least one row");
        assertThat(one.status()).as(one.body()).isEqualTo(201);
    }

    // ---- save half done, send complete ----

    @Test
    void aDraftIsSavedHalfDoneAndARequiredValueIsNeededToSendIt() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, cells(row("r1", "\"n\":\"1\"")));

        Response incomplete = sendFill(world.filler().browser(), fill, world.process());

        assertThat(incomplete.status()).isEqualTo(409);
        assertThat(message(incomplete)).contains("required");
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        assertThat(headerStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");

        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());
        assertThat(request).isNotBlank();
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
        assertThat(headerStatus(world.filler().browser(), fill)).as("the copy on the header follows")
                .isEqualTo("SUBMITTED");
    }

    @Test
    void aCellTheSheetFixesIsNeverAskedForAndARowOfTheSheetWithNothingStillNeedsItsRequiredValue() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        // Row r1 is complete; row r2 holds nothing at all, yet its required finding is needed.
        saved(world.filler().browser(), fill, cells(row("r1", "\"t\":\"x\"")));

        Response refused = sendFill(world.filler().browser(), fill, world.process());

        assertThat(refused.status()).isEqualTo(409);
        assertThat(message(refused)).contains("r2");
    }

    // ---- review: approved, returned, taken back ----

    @Test
    void anApprovedFillInIsFixedAndItsHeaderFollows() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());

        assertThat(approve(world.approver().browser(), request).status()).isEqualTo(200);

        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("APPROVED");
        assertThat(headerStatus(world.filler().browser(), fill)).isEqualTo("APPROVED");
        Response view = fillView(world.filler().browser(), fill);
        assertThat(JsonPath.<Boolean>read(view.body(), "$.data.canEdit")).isFalse();
        assertThat(saveCells(world.filler().browser(), fill, fillVersion(view), complete()).status())
                .isEqualTo(403);
        assertThat(sendFill(world.filler().browser(), fill, world.process()).status()).as("not again").isEqualTo(409);
    }

    @Test
    void aReturnedFillInGoesBackToItsFillerWhoChangesItAndSendsItAgain() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        String first = sent(world.filler().browser(), fill, world.process());

        assertThat(reject(world.approver().browser(), first).status()).isEqualTo(200);

        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("RETURNED");
        assertThat(headerStatus(world.filler().browser(), fill)).isEqualTo("RETURNED");
        assertThat(listedFills(world.filler().browser(), "status=RETURNED")).containsExactly(fill);
        saved(world.filler().browser(), fill, cells(row("r1", "\"t\":\"Door closed now\""), row("r2",
                "\"t\":\"Bell works\"")));
        String second = sent(world.filler().browser(), fill, world.process());
        assertThat(second).isNotEqualTo(first);
        assertThat(approve(world.approver().browser(), second).status()).isEqualTo(200);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("APPROVED");
        assertThat(JsonPath.<String>read(fillView(world.filler().browser(), fill).body(),
                "$.data.cells.rows[0].cells.t")).isEqualTo("Door closed now");
    }

    @Test
    void aFillerWhoTakesTheirFillInBackHasADraftAgain() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());

        assertThat(recall(world.filler().browser(), request).status()).isEqualTo(200);

        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        assertThat(headerStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        saved(world.filler().browser(), fill, cells(row("r1", "\"t\":\"changed\""), row("r2", "\"t\":\"x\"")));
    }

    // ---- edit after submit (decision 11) ----

    @Test
    void aSentFillInIsChangedOnlyByACountedCorrectionOfAPersonWhoMay() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());
        Response view = fillView(world.filler().browser(), fill);
        long version = fillVersion(view);

        Response byTheFiller = saveCells(world.filler().browser(), fill, version, complete());
        Response approverView = fillView(world.approver().browser(), fill);
        Response corrected = saveCells(world.approver().browser(), fill, fillVersion(approverView),
                cells(row("r1", "\"t\":\"Corrected\""), row("r2", "\"t\":\"Bell works\"")));

        assertThat(byTheFiller.status()).as("the filler may not change what they sent").isEqualTo(403);
        assertThat(JsonPath.<Boolean>read(approverView.body(), "$.data.canEdit")).isTrue();
        assertThat(JsonPath.<Boolean>read(approverView.body(), "$.data.correction")).isTrue();
        assertThat(corrected.status()).as(corrected.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(corrected.body(), "$.data.corrections")).isEqualTo(1);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
        assertThat(JsonPath.<String>read(fillView(world.filler().browser(), fill).body(),
                "$.data.cells.rows[0].cells.t")).isEqualTo("Corrected");
        assertThat(audited(world.setting().organization(), "sheets.fill.corrected")).isEqualTo(1);
        assertThat(approve(world.approver().browser(), request).status()).isEqualTo(200);
    }

    @Test
    void aCorrectionMayNotTakeARequiredValueAwayAndTheSameCellsAreNotACorrection() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        sent(world.filler().browser(), fill, world.process());
        Response view = fillView(world.approver().browser(), fill);

        Response emptied = saveCells(world.approver().browser(), fill, fillVersion(view), cells(row("r1",
                "\"n\":\"1\"")));
        Response same = saveCells(world.approver().browser(), fill, fillVersion(view), complete());

        assertThat(emptied.status()).isEqualTo(400);
        assertThat(same.status()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(same.body(), "$.data.corrections")).as("nothing changed, nothing counted")
                .isZero();
    }

    // ---- the person who is only asked to review (ADR-0189 section 5) ----

    @Test
    void aPersonAskedToReviewReadsThatOneFillInAndNothingElseAndNotAfterTheyHaveDecided() {
        World world = world();
        String asked = started(world.filler().browser(), world.template());
        String other = started(world.colleague().browser(), world.template());
        saved(world.filler().browser(), asked, complete());
        String request = sent(world.filler().browser(), asked, world.process());

        Response reads = fillView(world.checker().browser(), asked);
        Response cannot = fillView(world.checker().browser(), other);

        assertThat(reads.status()).as(reads.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(reads.body(), "$.data.canEdit")).as("reading, not changing").isFalse();
        assertThat(cannot.status()).isEqualTo(404);
        assertThat(saveCells(world.checker().browser(), asked, fillVersion(reads), complete()).status())
                .isIn(403, 404);
        assertThat(approve(world.checker().browser(), request).status()).isEqualTo(200);
        assertThat(fillView(world.checker().browser(), asked).status()).as("no open task any more").isEqualTo(404);
    }

    // ---- after the template moves on (criterion 13) ----

    @Test
    void aFillInIsFinishedAndSentAfterTheTemplateIsUnpublished() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        assertThat(unpublish(world.approver().browser(), world.template()).status()).isEqualTo(200);

        Response view = fillView(world.filler().browser(), fill);
        assertThat(view.status()).as("readable, although the template is not any more").isEqualTo(200);
        assertThat(JsonPath.<String>read(view.body(), "$.data.fill.versionStatus")).isEqualTo("APPROVED");
        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());

        assertThat(approve(world.approver().browser(), request).status()).isEqualTo(200);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("APPROVED");
        assertThat(startFill(world.filler().browser(), world.template()).status()).as("but no new one")
                .isEqualTo(404);
    }

    @Test
    void aFillInStaysReadableWhenTheTemplateIsRetiredButCanNoLongerBeChangedOrSent() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);

        Response view = fillView(world.filler().browser(), fill);

        assertThat(view.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(view.body(), "$.data.fill.versionStatus")).isEqualTo("RETIRED");
        assertThat(JsonPath.<Boolean>read(view.body(), "$.data.canEdit")).isFalse();
        assertThat(JsonPath.<String>read(view.body(), "$.data.cells.rows[0].cells.t")).isEqualTo("Door open");
        assertThat(saveCells(world.filler().browser(), fill, fillVersion(view), complete()).status())
                .isEqualTo(409);
        Response send = sendFill(world.filler().browser(), fill, world.process());
        assertThat(send.status()).isEqualTo(409);
        assertThat(message(send)).contains("retired");
        assertThat(listedFills(world.filler().browser(), "")).as("still listed").contains(fill);
    }

    // ---- the list (ADR-0173 section 6) ----

    @Test
    void theListIsNewestFirstWithoutTheCellsAndPagesByKeyToTheEnd() {
        World world = world();
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String fill = started(world.filler().browser(), world.template());
            saved(world.filler().browser(), fill, cells(row("r1", "\"t\":\"typed-" + i + "\"")));
            made.add(fill);
        }
        started(world.colleague().browser(), world.template());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = world.filler().browser().get(FILL_INS + "?size=2" + (cursor == null ? ""
                    : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            assertThat(page.body()).as("a list never carries the cells").doesNotContain("typed-")
                    .doesNotContain("\"cells\"");
            seen.addAll(JsonPath.<List<String>>read(page.body(), "$.data.rows[*].fillId"));
            cursor = cursorOf(page);
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        List<String> newestFirst = new ArrayList<>(made);
        java.util.Collections.reverse(newestFirst);
        assertThat(seen).as("only their own, newest first, each once").containsExactlyElementsOf(newestFirst);
    }

    /** The cursor of the next page; a last page leaves it out (responses omit null fields). */
    private static String cursorOf(Response page) {
        try {
            return JsonPath.read(page.body(), "$.data.nextCursor");
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    @Test
    void theListNarrowsByTemplateAndStatusAndShowsWhoAndWhatVersion() {
        World world = world();
        String draft = started(world.filler().browser(), world.template());
        String done = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), done, complete());
        approve(world.approver().browser(), sent(world.filler().browser(), done, world.process()));

        Response page = world.filler().browser().get(FILL_INS + "?templateId=" + world.template());
        List<String> approved = listedFills(world.filler().browser(), "status=APPROVED");
        List<String> drafts = listedFills(world.filler().browser(), "status=DRAFT&templateId=" + world.template());

        assertThat(JsonPath.<List<String>>read(page.body(), "$.data.rows[*].templateId"))
                .containsOnly(world.template());
        assertThat(JsonPath.<List<Integer>>read(page.body(), "$.data.rows[*].versionNo")).containsOnly(1);
        assertThat(JsonPath.<List<Boolean>>read(page.body(), "$.data.rows[*].versionInUse")).containsOnly(true);
        assertThat(approved).containsExactly(done);
        assertThat(drafts).containsExactly(draft);
        assertThat(world.filler().browser().get(FILL_INS + "?status=SOMETHING").status()).isEqualTo(400);
    }

    @Test
    void aReviewerAndAnApproverSeeEveryFillInAndAFillerOnlyTheirOwn() {
        World world = world();
        String mine = started(world.filler().browser(), world.template());
        String theirs = started(world.colleague().browser(), world.template());

        assertThat(listedFills(world.filler().browser(), "")).containsExactly(mine);
        assertThat(listedFills(world.colleague().browser(), "")).containsExactly(theirs);
        assertThat(listedFills(world.reviewer().browser(), "")).containsExactlyInAnyOrder(mine, theirs);
        assertThat(listedFills(world.approver().browser(), "")).containsExactlyInAnyOrder(mine, theirs);
        TestBrowser reviewer = world.reviewer().browser();
        assertThat(fillView(reviewer, mine).status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(fillView(reviewer, mine).body(), "$.data.canEdit")).isFalse();
    }
}
