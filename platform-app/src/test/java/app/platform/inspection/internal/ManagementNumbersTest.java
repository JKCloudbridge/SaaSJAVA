package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.ManagementHomeViews.MonthStatus;
import app.platform.inspection.internal.ManagementHomeViews.PlantPlans;
import app.platform.inspection.internal.ManagementHomeViews.PlantScore;
import app.platform.inspection.internal.ManagementHomeViews.PlantSeries;
import app.platform.inspection.internal.ManagementNumbers.Bands;
import app.platform.inspection.internal.ManagementNumbers.Row;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of the dashboard that compares the plants (MC3, ADR-0262), with no database: which fill-ins count, how
 * a plant's score for a month is made, the bands at their edges, the ranks, the gaps and the Action plan counts.
 */
class ManagementNumbersTest {

    private static final Bands BANDS = new Bands(85, 85, 65);
    private static final Map<String, String> LABELS = Map.of("P1", "Plant 1", "P2", "Plant 2", "P3", "Plant 3");

    private static Row fill(String plant, String period, String score, String status, String result) {
        return new Row(UUID.randomUUID(), UUID.randomUUID().toString(), "Plant activity", plant, period,
                score == null ? null : new BigDecimal(score), result, status, null);
    }

    private static Row approved(String plant, String period, String score) {
        return fill(plant, period, score, "APPROVED", Double.parseDouble(score) < 85 ? "ACTION_PLAN" : "PASS");
    }

    private static Row plan(String plant, String status, UUID parent) {
        return new Row(UUID.randomUUID(), UUID.randomUUID().toString(), "Action plan", plant, "2026-11", null, null,
                status, parent);
    }

    // ---- which fill-ins count, and how a plant's score is made ----

    @Test
    void aPlantsScoreIsTheAverageOfItsApprovedFillInsRoundedHalfUpToTwoDecimals() {
        List<Row> rows = List.of(approved("P1", "2026-11", "80.00"), approved("P1", "2026-11", "91.01"),
                approved("P2", "2026-11", "70.005"));

        Map<String, Double> scores = ManagementNumbers.plantScores(rows, "2026-11");

        assertThat(scores.get("P1")).isEqualTo(85.51);
        assertThat(scores.get("P2")).as("half up, never to even").isEqualTo(70.01);
    }

    @Test
    void onlyApprovedFillInsCountAndAnActionPlanNeverIs() {
        UUID parent = UUID.randomUUID();
        List<Row> rows = new ArrayList<>(List.of(approved("P1", "2026-11", "90"),
                fill("P1", "2026-11", "10", "SUBMITTED", "ACTION_PLAN"), fill("P1", "2026-11", "10", "DRAFT", null),
                fill("P1", "2026-11", "10", "RETURNED", "ACTION_PLAN")));
        rows.add(new Row(UUID.randomUUID(), "x", "Action plan", "P1", "2026-11", new BigDecimal("5"), "ACTION_PLAN",
                "APPROVED", parent));

        assertThat(ManagementNumbers.plantScores(rows, "2026-11")).containsExactly(Map.entry("P1", 90.0));
    }

    @Test
    void aFillInWithNoScoreNoPlantOrNoMonthHasNoScoreToCount() {
        List<Row> rows = List.of(fill("P1", "2026-11", null, "APPROVED", null),
                fill(null, "2026-11", "80", "APPROVED", "ACTION_PLAN"), fill("P2", null, "80", "APPROVED", null));

        assertThat(ManagementNumbers.plantScores(rows, "2026-11")).isEmpty();
        assertThat(ManagementNumbers.averageOfPlants(rows, "2026-11")).isNull();
    }

    @Test
    void theAverageOfTheMonthIsTheAverageOfThePlantsNotOfTheFillIns() {
        List<Row> rows = List.of(approved("P1", "2026-11", "100"), approved("P1", "2026-11", "100"),
                approved("P1", "2026-11", "100"), approved("P2", "2026-11", "40"));

        assertThat(ManagementNumbers.averageOfPlants(rows, "2026-11")).as("(100 + 40) / 2, not 85").isEqualTo(70.0);
    }

    @Test
    void aMonthNobodyReportedHasNoAverageAndIsNeverZero() {
        List<Row> rows = List.of(approved("P1", "2026-10", "90"));

        assertThat(ManagementNumbers.averageOfPlants(rows, "2026-11")).isNull();
        assertThat(ManagementNumbers.averageByMonth(rows, List.of("2026-10", "2026-11")))
                .extracting(ManagementHomeViews.MonthAverage::average).containsExactly(90.0, null);
        assertThat(ManagementNumbers.averageByMonth(rows, List.of("2026-11")).get(0).plants()).isZero();
    }

    // ---- the bands ----

    @Test
    void theBandsAreWordedAboveTheirLineAndTheEdgesBelongToTheLowerBand() {
        assertThat(BANDS.bandOf(100)).isEqualTo("EXCELLENT");
        assertThat(BANDS.bandOf(85.01)).isEqualTo("EXCELLENT");
        assertThat(BANDS.bandOf(85)).as("exactly the Excellent line is Good").isEqualTo("GOOD");
        assertThat(BANDS.bandOf(65.01)).isEqualTo("GOOD");
        assertThat(BANDS.bandOf(65)).as("exactly the Good line is Poor").isEqualTo("POOR");
        assertThat(BANDS.bandOf(0)).isEqualTo("POOR");
        assertThat(Bands.word("EXCELLENT")).isEqualTo("Excellent");
        assertThat(Bands.word("GOOD")).isEqualTo("Good");
        assertThat(Bands.word("POOR")).isEqualTo("Poor");
        assertThat(Bands.word(null)).isNull();
    }

    @Test
    void theBandsFollowTheNumbersInForceAndTheLimitIsNotTheLegend() {
        Bands stricter = new Bands(85, 95, 80);

        assertThat(stricter.bandOf(90)).isEqualTo("GOOD");
        assertThat(stricter.bandOf(80)).isEqualTo("POOR");
        assertThat(new Bands(85, null, null).bandOf(90)).as("an application with no legend has no word").isNull();
    }

    // ---- the plants of a month: bars, ranks, gaps ----

    @Test
    void thePlantsAreSortedBestFirstEqualScoresShareARankAndAPlantWithNoScoreIsLastWithNone() {
        List<Row> rows = List.of(approved("P1", "2026-11", "90"), approved("P2", "2026-11", "90"),
                approved("P3", "2026-11", "70"));
        Map<String, String> labels = new java.util.LinkedHashMap<>(LABELS);
        labels.put("P4", "Plant 4");

        List<PlantScore> plants = ManagementNumbers.scoresByPlant(rows, "2026-11", labels, BANDS);

        assertThat(plants).extracting(PlantScore::plant).containsExactly("P1", "P2", "P3", "P4");
        assertThat(plants).extracting(PlantScore::rank).containsExactly(1, 1, 3, null);
        assertThat(plants.get(3).score()).as("a gap, never a zero").isNull();
        assertThat(plants.get(3).bandWord()).isNull();
        assertThat(plants.get(0).bandWord()).isEqualTo("Excellent");
        assertThat(plants.get(2).bandWord()).isEqualTo("Good");
    }

    @Test
    void aPlantThatHasAScoreButIsNotListedIsStillShown() {
        List<Row> rows = List.of(approved("P9", "2026-11", "75"));

        List<PlantScore> plants = ManagementNumbers.scoresByPlant(rows, "2026-11", Map.of("P1", "Plant 1"), BANDS);

        assertThat(plants).extracting(PlantScore::plant).containsExactly("P9", "P1");
        assertThat(plants.get(0).label()).isEqualTo("P9");
    }

    @Test
    void theDecisionThatAnActionPlanIsNeededIsTheStoredResultAndCanDifferFromTheLimitInForceNow() {
        // 80 was decided under a limit of 75: its result says PASS; the limit now is 85, so it is below it.
        List<Row> rows = List.of(fill("P1", "2026-11", "80", "APPROVED", "PASS"),
                fill("P2", "2026-11", "90", "APPROVED", "ACTION_PLAN"));

        List<PlantScore> plants = ManagementNumbers.scoresByPlant(rows, "2026-11", LABELS, new Bands(85, 85, 65));

        PlantScore one = plants.stream().filter(plant -> plant.plant().equals("P1")).findFirst().orElseThrow();
        PlantScore two = plants.stream().filter(plant -> plant.plant().equals("P2")).findFirst().orElseThrow();
        assertThat(one.needsActionPlan()).isFalse();
        assertThat(one.belowCurrentLimit()).isTrue();
        assertThat(two.needsActionPlan()).isTrue();
        assertThat(two.belowCurrentLimit()).isFalse();
    }

    @Test
    void theLimitItselfIsNotBelowIt() {
        List<Row> rows = List.of(approved("P1", "2026-11", "85"));

        PlantScore plant = ManagementNumbers.scoresByPlant(rows, "2026-11", LABELS, BANDS).get(0);

        assertThat(plant.belowCurrentLimit()).isFalse();
        assertThat(plant.needsActionPlan()).isFalse();
    }

    // ---- the months: lines with gaps ----

    @Test
    void aMonthAPlantDidNotReportIsAGapInItsLineAndNeverZero() {
        List<Row> rows = List.of(approved("P1", "2026-09", "70"), approved("P1", "2026-11", "88"),
                approved("P2", "2026-10", "92"));
        List<String> months = List.of("2026-09", "2026-10", "2026-11");

        List<PlantSeries> series = ManagementNumbers.plantsOverMonths(rows, months, LABELS);

        assertThat(series).extracting(PlantSeries::plant).containsExactly("P1", "P2");
        assertThat(series.get(0).points()).extracting(ManagementHomeViews.MonthScore::score)
                .containsExactly(70.0, null, 88.0);
        assertThat(series.get(1).points()).extracting(ManagementHomeViews.MonthScore::score)
                .containsExactly(null, 92.0, null);
    }

    @Test
    void aPlantWithNoScoreInAnyMonthOfTheSpanHasNoLine() {
        List<Row> rows = List.of(approved("P1", "2026-01", "70"));

        assertThat(ManagementNumbers.plantsOverMonths(rows, List.of("2026-10", "2026-11"), LABELS)).isEmpty();
    }

    @Test
    void monthsEndWithTheMonthChosenAndCrossTheYear() {
        assertThat(ManagementNumbers.monthsEnding(YearMonth.of(2026, 2), 4))
                .containsExactly("2025-11", "2025-12", "2026-01", "2026-02");
        assertThat(ManagementNumbers.monthsEnding(YearMonth.of(2026, 11), 1)).containsExactly("2026-11");
    }

    // ---- who sent, who needs a plan ----

    @Test
    void aPlantHasSentWhenAFillInIsWaitingOrDecidedAndNotWhenItIsADraftOrReturned() {
        List<Row> rows = List.of(fill("P1", "2026-11", null, "SUBMITTED", null),
                fill("P2", "2026-11", "90", "APPROVED", "PASS"),
                fill("P3", "2026-11", null, "DRAFT", null), fill("P4", "2026-11", null, "RETURNED", null),
                fill("P1", "2026-10", null, "SUBMITTED", null));

        assertThat(ManagementNumbers.plantsSubmitted(rows, "2026-11")).containsExactly("P1", "P2");
        assertThat(ManagementNumbers.plantsSubmitted(rows, "2026-10")).containsExactly("P1");
    }

    @Test
    void aFillInThatWaitsAtTheLastStepWithAnActionPlanDecisionCountsAsNeedingOneBeforeItIsApproved() {
        List<Row> rows = List.of(fill("P1", "2026-11", "70", "SUBMITTED", "ACTION_PLAN"),
                fill("P2", "2026-11", "70", "RETURNED", "ACTION_PLAN"),
                fill("P3", "2026-11", "70", "DRAFT", "ACTION_PLAN"));

        assertThat(ManagementNumbers.needingPlan(rows, "2026-11")).extracting(Row::plant).containsExactly("P1");
    }

    @Test
    void anActionPlanThatAnswersAFillInTakesItOutOfThoseWithoutOne() {
        Row one = fill("P1", "2026-11", "70", "APPROVED", "ACTION_PLAN");
        Row two = fill("P2", "2026-11", "60", "SUBMITTED", "ACTION_PLAN");
        List<Row> plans = List.of(plan("P1", "DRAFT", one.id()));

        Set<UUID> answered = ManagementNumbers.answered(plans);

        assertThat(ManagementNumbers.withoutPlan(List.of(one, two), answered)).isEqualTo(1);
        assertThat(ManagementNumbers.withoutPlan(List.of(one, two), Set.of())).isEqualTo(2);
    }

    // ---- Action plans and the status of the months ----

    @Test
    void actionPlansAreSplitByWhereTheyStandAndOpenMeansNotApproved() {
        UUID parent = UUID.randomUUID();
        List<Row> plans = List.of(plan("P1", "DRAFT", parent), plan("P1", "RETURNED", parent),
                plan("P1", "SUBMITTED", parent), plan("P1", "APPROVED", parent), plan("P2", "SUBMITTED", parent),
                plan(null, "DRAFT", parent), fill("P1", "2026-11", "1", "APPROVED", null));

        List<PlantPlans> by = ManagementNumbers.plansByPlant(plans, LABELS, "Not recorded");

        assertThat(by).extracting(PlantPlans::label).containsExactly("Not recorded", "Plant 1", "Plant 2");
        PlantPlans first = by.get(1);
        assertThat(first.open()).isEqualTo(2);
        assertThat(first.sent()).isEqualTo(1);
        assertThat(first.approved()).isEqualTo(1);
        assertThat(ManagementNumbers.open(plans)).as("everything that is an Action plan and not approved").isEqualTo(5);
    }

    @Test
    void theFillInsOfEachMonthAreCountedByStatusAndActionPlansAreLeftOut() {
        UUID parent = UUID.randomUUID();
        List<Row> rows = List.of(fill("P1", "2026-10", null, "DRAFT", null),
                fill("P1", "2026-10", null, "SUBMITTED", null),
                fill("P2", "2026-10", null, "RETURNED", null), fill("P2", "2026-11", "90", "APPROVED", "PASS"),
                fill("P2", "2026-09", "90", "APPROVED", "PASS"), plan("P1", "APPROVED", parent));

        List<MonthStatus> months = ManagementNumbers.fillsByMonth(rows, List.of("2026-10", "2026-11"));

        assertThat(months).hasSize(2);
        assertThat(months.get(0)).isEqualTo(new MonthStatus("2026-10", 1L, 1L, 1L, 0L));
        assertThat(months.get(1)).isEqualTo(new MonthStatus("2026-11", 0L, 0L, 0L, 1L));
    }

    @Test
    void averagesOfNothingAreNullNotZero() {
        assertThat(ManagementNumbers.average(List.of())).isNull();
        assertThat(ManagementNumbers.average(List.of(new BigDecimal("1.005"), new BigDecimal("1.005"))))
                .isEqualTo(1.01);
    }

    @Test
    void aRowPrintsItsIdentityOnlyNeverAValue() {
        Row row = approved("P1", "2026-11", "91.5");

        assertThat(row.toString()).startsWith("Row[").doesNotContain("91.5").doesNotContain("P1");
    }
}
