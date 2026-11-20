package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardCell;
import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardPlan;
import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardPlant;
import app.platform.inspection.internal.ManagementNumbers.Row;
import app.platform.inspection.internal.PlantBoardNumbers.Board;
import app.platform.inspection.internal.PlantBoardNumbers.Inputs;
import app.platform.inspection.internal.PlantBoardNumbers.Judgement;
import app.platform.inspection.internal.PlantBoardNumbers.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules of the plant board (ADR-0266), with no database and no clock: the status of every kind of Action plan, a
 * day that is never guessed, the cells, the tiles, the gaps, the totals and the order of the detail table.
 */
class PlantBoardNumbersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final String P1 = "P1";
    private static final String P2 = "P2";

    private static Row fill(String plant, String month, String score, String result, String status,
            String scoredOn) {
        return new Row(UUID.randomUUID(), "t", "Form", plant, month, score == null ? null : new BigDecimal(score),
                result, status, null, scoredOn, null);
    }

    private static Row planOf(Row fill, String status, String sentOn) {
        return new Row(UUID.randomUUID(), "t", "Plan", fill.plant(), fill.period(), null, null, status, fill.id(),
                null, sentOn);
    }

    private static Row low(String plant, String month, String score, String scoredOn) {
        return fill(plant, month, score, "ACTION_PLAN", "APPROVED", scoredOn);
    }

    private static Row ok(String plant, String month, String score) {
        return fill(plant, month, score, "PASS", "APPROVED", "2026-01-01");
    }

    // ---- one plan ----

    @Test
    void aPlanSentOnTheDueDayIsOnTimeAndOneDayLaterIsLate() {
        Row fill = low(P1, "2026-03", "83.00", "2026-04-06");

        assertThat(judge(fill, planOf(fill, "SUBMITTED", "2026-04-16")).status()).isEqualTo(Status.SUBMITTED);
        assertThat(judge(fill, planOf(fill, "SUBMITTED", "2026-04-17")).status()).isEqualTo(Status.LATE);
        assertThat(judge(fill, planOf(fill, "SUBMITTED", "2026-04-10")).sentOn()).isEqualTo(LocalDate.of(2026, 4, 10));
    }

    @Test
    void aPlanNotSentIsAwaitingUpToAndOnTheDueDayAndOverdueTheDayAfter() {
        Row fill = low(P1, "2026-10", "80.00", "2026-11-10");

        assertThat(judgeOn(fill, null, LocalDate.of(2026, 11, 20)).status()).isEqualTo(Status.AWAITING);
        Judgement overdue = judgeOn(fill, null, LocalDate.of(2026, 11, 21));
        assertThat(overdue.status()).isEqualTo(Status.OVERDUE);
        assertThat(overdue.overdueDays()).isEqualTo(1);
        assertThat(overdue.text()).isEqualTo("Overdue 1 day");
        assertThat(judgeOn(fill, null, LocalDate.of(2026, 12, 1)).text()).isEqualTo("Overdue 11 days");
    }

    @Test
    void aPlanThatIsStartedButNotSentIsJudgedLikeOneThatIsNotStarted() {
        Row fill = low(P1, "2026-05", "80.00", "2026-06-01");

        assertThat(judge(fill, planOf(fill, "DRAFT", null)).status()).isEqualTo(Status.OVERDUE);
        assertThat(judge(fill, planOf(fill, "RETURNED", null)).status()).isEqualTo(Status.OVERDUE);
    }

    @Test
    void aSecondSendKeepsTheFirstDayBecauseOnlyTheFirstIsStamped() {
        Row fill = low(P1, "2026-03", "83.00", "2026-04-06");

        assertThat(judge(fill, planOf(fill, "RETURNED", "2026-04-10")).status()).isEqualTo(Status.SUBMITTED);
    }

    @Test
    void aDayThatWasNeverStampedIsNeverGuessed() {
        Row noScoreDay = low(P1, "2026-03", "83.00", null);
        Row sentBeforeStamps = low(P1, "2026-03", "83.00", "2026-04-06");

        assertThat(judge(noScoreDay, null).status()).isEqualTo(Status.NO_DATE);
        assertThat(judge(sentBeforeStamps, planOf(sentBeforeStamps, "SUBMITTED", null)).status())
                .isEqualTo(Status.NO_DATE);
        assertThat(judge(sentBeforeStamps, planOf(sentBeforeStamps, "APPROVED", null)).status())
                .isEqualTo(Status.NO_DATE);
        assertThat(PlantBoardNumbers.day("2026-02-30")).isNull();
        assertThat(PlantBoardNumbers.day("  ")).isNull();
    }

    @Test
    void anApplicationWithNoDueDaysJudgesNoPlan() {
        Row fill = low(P1, "2026-03", "83.00", "2026-04-06");

        assertThat(PlantBoardNumbers.judge(fill, null, null, TODAY).status()).isEqualTo(Status.NO_DATE);
    }

    @Test
    void theDueDaysCrossAMonthAndAYear() {
        Row fill = low(P1, "2025-12", "70.00", "2025-12-28");

        Judgement judgement = judgeOn(fill, null, LocalDate.of(2026, 1, 8));

        assertThat(judgement.dueOn()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(judgement.status()).isEqualTo(Status.OVERDUE);
    }

    private static Judgement judge(Row fill, Row plan) {
        return judgeOn(fill, plan, TODAY);
    }

    private static Judgement judgeOn(Row fill, Row plan, LocalDate today) {
        return PlantBoardNumbers.judge(fill, plan, 10, today);
    }

    // ---- the board ----

    private static Board board(List<String> months, String current, List<Row> fills, List<Row> plans) {
        Map<String, String> plants = new LinkedHashMap<>();
        plants.put(P1, "Plant 1");
        plants.put(P2, "Plant 2");
        Map<String, Integer> colours = Map.of(P1, 1, P2, 2);
        return PlantBoardNumbers.build(new Inputs(months, current, plants, colours, fills, plans, 10, TODAY));
    }

    @Test
    void everyKindOfCellSaysItsWordAndAMonthWithNoScoreIsAGapNeverAZero() {
        Row great = ok(P1, "2026-08", "90.00");
        Row lowOnTime = low(P1, "2026-09", "80.00", "2026-10-01");
        Row lowOverdue = low(P1, "2026-10", "80.00", "2026-10-02");
        List<Row> fills = List.of(great, lowOnTime, lowOverdue);
        List<Row> plans = List.of(planOf(lowOnTime, "SUBMITTED", "2026-10-05"));

        Board board = board(List.of("2026-08", "2026-09", "2026-10", "2026-07"), null, fills, plans);
        List<ManagementBoardCell> cells = board.grid().get(0).cells();

        assertThat(cells).extracting(ManagementBoardCell::status).containsExactly("NO_PLAN", "SUBMITTED",
                "OVERDUE", "NO_SCORE");
        assertThat(cells).extracting(ManagementBoardCell::word).containsExactly("No plan", "Submitted", "Overdue",
                "No score");
        assertThat(cells.get(3).score()).isNull();
        assertThat(board.plants().get(0).points()).containsExactly(90.0, 80.0, 80.0, null);
        // The second plant reported nothing: every cell is a gap.
        assertThat(board.grid().get(1).cells()).extracting(ManagementBoardCell::status).containsOnly("NO_SCORE");
    }

    @Test
    void aScoreEqualToTheLimitWasStoredAsAPassAndNeedsNoPlan() {
        Row atLimit = fill(P1, "2026-10", "85.00", "PASS", "APPROVED", "2026-11-01");

        Board board = board(List.of("2026-10"), null, List.of(atLimit), List.of());

        assertThat(board.grid().get(0).cells().get(0).status()).isEqualTo("NO_PLAN");
        assertThat(board.plans()).isEmpty();
    }

    @Test
    void theMonthInProgressIsInReviewWhenSentAwaitingWhenNotAndOtherwiseLikeAnyMonthOnceApproved() {
        Row sent = fill(P1, "2026-11", null, null, "SUBMITTED", null);
        Row approvedBelow = low(P2, "2026-11", "70.00", "2026-11-18");

        Board board = board(List.of("2026-10"), "2026-11", List.of(sent, approvedBelow), List.of());

        assertThat(board.grid().get(0).current().status()).isEqualTo("IN_REVIEW");
        assertThat(board.grid().get(0).current().score()).isNull();
        assertThat(board.grid().get(1).current().status()).isEqualTo("AWAITING");
        assertThat(board.grid().get(1).current().score()).isEqualTo(70.0);

        Board nothing = board(List.of("2026-10"), "2026-11", List.of(), List.of());
        assertThat(nothing.grid().get(0).current().status()).isEqualTo("NOT_SENT");
        assertThat(nothing.grid().get(0).current().word()).isEqualTo("Awaiting");
        assertThat(board(List.of("2026-10"), null, List.of(), List.of()).grid().get(0).current()).isNull();
    }

    @Test
    void aTileHasTheLatestScoreTheChangeAgainstTheNearestEarlierMonthWithAScoreAndItsName() {
        List<Row> fills = List.of(ok(P1, "2026-07", "88.00"), ok(P1, "2026-09", "90.00"), ok(P1, "2026-10", "91.00"),
                ok(P2, "2026-08", "86.00"), ok(P2, "2026-10", "87.00"));

        Board board = board(List.of("2026-07", "2026-08", "2026-09", "2026-10"), null, fills, List.of());
        ManagementBoardPlant one = board.plants().get(0);
        ManagementBoardPlant two = board.plants().get(1);

        assertThat(one.latestMonth()).isEqualTo("2026-10");
        assertThat(one.latestScore()).isEqualTo(91.0);
        assertThat(one.change()).isEqualTo(1.0);
        assertThat(one.changeMonth()).isEqualTo("2026-09");
        assertThat(one.scoresSubmitted()).isEqualTo(3);
        assertThat(one.scoresOf()).isEqualTo(4);
        // The month before was a gap, so the change is against the nearest month that has a score.
        assertThat(two.change()).isEqualTo(1.0);
        assertThat(two.changeMonth()).isEqualTo("2026-08");
        assertThat(two.colour()).isEqualTo(2);
    }

    @Test
    void aPlantWhoseLatestMonthIsAGapHasNoScoreAndNoChange() {
        Board board = board(List.of("2026-09", "2026-10"), null, List.of(ok(P1, "2026-09", "90.00")), List.of());

        assertThat(board.plants().get(0).latestScore()).isNull();
        assertThat(board.plants().get(0).change()).isNull();
    }

    @Test
    void aMonthScoreIsTheAverageOfTheApprovedFillInsOfThePlantAndOneThatIsNotApprovedIsNotCounted() {
        List<Row> fills = List.of(ok(P1, "2026-10", "80.00"), ok(P1, "2026-10", "90.00"),
                fill(P1, "2026-10", "10.00", null, "RETURNED", null));

        Board board = board(List.of("2026-10"), null, fills, List.of());

        assertThat(board.plants().get(0).latestScore()).isEqualTo(85.0);
    }

    @Test
    void theTotalsAndTheTilesCountPlansSentSubmittedAndOverdueAndLeaveOutADayThatWasNeverStamped() {
        Row a = low(P1, "2026-01", "82.00", "2026-02-05");
        Row b = low(P1, "2026-02", "84.00", "2026-03-05");
        Row c = low(P2, "2026-03", "83.00", "2026-04-06");
        Row d = low(P2, "2026-04", "80.00", "2026-11-18");
        Row old = low(P2, "2026-05", "79.00", null);
        List<Row> plans = List.of(planOf(a, "SUBMITTED", "2026-02-12"), planOf(b, "SUBMITTED", "2026-03-20"));

        Board board = board(List.of("2026-01", "2026-02", "2026-03", "2026-04", "2026-05"), null,
                List.of(a, b, c, d, old), plans);

        assertThat(board.totals().plansSent()).isEqualTo(4);
        assertThat(board.totals().plansSubmitted()).isEqualTo(2);
        assertThat(board.totals().overdue()).isEqualTo(1);
        ManagementBoardPlant one = board.plants().get(0);
        assertThat(one.plansSent()).isEqualTo(2);
        assertThat(one.plansSubmitted()).isEqualTo(2);
        assertThat(one.overdue()).isZero();
        ManagementBoardPlant two = board.plants().get(1);
        assertThat(two.plansSent()).isEqualTo(2);
        assertThat(two.plansSubmitted()).isZero();
        assertThat(two.overdue()).isEqualTo(1);
    }

    @Test
    void theDetailTableListsTheMostUrgentFirstThenTheNewestMonthThenThePlant() {
        Row submitted = low(P1, "2026-02", "84.00", "2026-03-05");
        Row overdueOld = low(P2, "2026-03", "83.00", "2026-04-06");
        Row overdueNew = low(P1, "2026-09", "83.00", "2026-10-06");
        Row awaiting = low(P2, "2026-10", "80.00", "2026-11-15");
        List<Row> plans = List.of(planOf(submitted, "SUBMITTED", "2026-03-11"));

        Board board = board(List.of("2026-02", "2026-03", "2026-09", "2026-10"), null,
                List.of(submitted, overdueOld, overdueNew, awaiting), plans);

        List<ManagementBoardPlan> rows = board.plans();
        assertThat(rows).extracting(ManagementBoardPlan::status).containsExactly("OVERDUE", "OVERDUE", "AWAITING",
                "SUBMITTED");
        assertThat(rows).extracting(ManagementBoardPlan::month).containsExactly("2026-09", "2026-03", "2026-10",
                "2026-02");
        assertThat(rows.get(1).statusText()).isEqualTo("Overdue 218 days");
        assertThat(rows.get(1).dueOn()).isEqualTo("2026-04-16");
        assertThat(rows.get(3).submittedOn()).isEqualTo("2026-03-11");
        assertThat(rows.get(3).planId()).isNotNull();
        assertThat(rows.get(0).planId()).isNull();
    }

    @Test
    void anActionPlanIsNeverAScoreAndOnlyTheFillInsOfThePlantsShownAreInTheBoard() {
        List<Row> fills = new ArrayList<>(List.of(ok(P1, "2026-10", "90.00")));
        Row plan = new Row(UUID.randomUUID(), "t", "Plan", P1, "2026-10", new BigDecimal("12.00"), null, "APPROVED",
                UUID.randomUUID(), null, null);

        Board board = board(List.of("2026-10"), null, fills, List.of(plan));

        assertThat(board.plants().get(0).latestScore()).isEqualTo(90.0);
        assertThat(board.plans()).isEmpty();
    }

    // ---- the months ----

    @Test
    void leadingMonthsInWhichNothingWasRecordedAreLeftOutAndAGapInTheMiddleIsKept() {
        List<Row> fills = List.of(ok(P1, "2026-03", "90.00"), ok(P1, "2026-05", "90.00"));

        assertThat(PlantBoardNumbers.trimmed(List.of("2026-01", "2026-02", "2026-03", "2026-04", "2026-05"), fills))
                .containsExactly("2026-03", "2026-04", "2026-05");
        assertThat(PlantBoardNumbers.trimmed(List.of("2026-01", "2026-02"), fills)).isEmpty();
    }
}
