package app.platform.inspection.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the Home dashboard that compares the plants sends and receives (MC3, ADR-0262). Like the views of the Quality
 * Management System home page they live here and not in the API contract module, because they carry the cluster's
 * words; the OpenAPI document and the generated client carry them all the same.
 *
 * <p>Everything is for a screen to draw, never to decide. A section that is null is one the caller may not see at all
 * (the object behind it cannot be read): the screen draws nothing for it, not a zero. A section that failed is named in
 * {@code problems}. A score that does not exist is {@code null}, never zero: a plant that did not report is a gap.
 * Scores are in percent points with two decimals. They are the organization's data and never reach a log.
 */
public final class ManagementHomeViews {

    private ManagementHomeViews() {
    }

    /**
     * The question: the month to look at and how far back to compare, and optionally one plant and one form. Typed
     * text never goes in an address, so this is a body.
     *
     * @param month {@code this}, {@code last} or a month as {@code yyyy-MM} (UTC); empty means this month
     * @param span how many months the comparing charts cover, ending with the month: 3, 6 or 12; empty means 6
     * @param plant a plant code the caller may see, or null for all
     * @param form the identifier of one document whose fill-ins are counted, or null for all
     */
    public record ManagementSummaryRequest(String month, Integer span, String plant, UUID form) {

        /** Prints nothing typed. */
        @Override
        public String toString() {
            return "ManagementSummaryRequest[redacted]";
        }
    }

    /**
     * The answer.
     *
     * @param generatedAt when it was worked out
     * @param month the month the tiles and the month charts are about ({@code yyyy-MM}, UTC)
     * @param previousMonth the month before it, which the tiles compare with
     * @param months the months of the comparing charts, oldest first, ending with {@code month}
     * @param monthChoices the months a person may choose, newest first
     * @param filters what the filter bar offers
     * @param applied the filters that were applied, after checking them against what the caller may see
     * @param legend the limit and the words of the bands in force
     * @param tiles the numbers on top
     * @param charts the graphs and lists below
     * @param problems the sections that could not be worked out, by name; empty when all were
     */
    public record ManagementSummaryView(@NotNull Instant generatedAt, @NotNull String month,
            @NotNull String previousMonth,
            @NotNull List<String> months, @NotNull List<String> monthChoices, @NotNull ManagementFiltersView filters,
            @NotNull ManagementAppliedView applied, @NotNull LegendView legend, @NotNull ManagementTilesView tiles,
            @NotNull ManagementChartsView charts, @NotNull List<String> problems) {
    }

    /**
     * The filter bar's choices.
     *
     * @param plants the plants the caller may see, sorted by label
     * @param forms the documents that have fill-ins the caller may see, sorted by name
     */
    public record ManagementFiltersView(@NotNull List<PlantOption> plants, @NotNull List<FormOption> forms) {
    }

    /**
     * One plant.
     *
     * @param code the code to send back
     * @param label what people read
     */
    public record PlantOption(@NotNull String code, @NotNull String label) {
    }

    /**
     * One document.
     *
     * @param id the document
     * @param name the name its fill-ins carry
     */
    public record FormOption(@NotNull UUID id, @NotNull String name) {
    }

    /**
     * The filters that were applied.
     *
     * @param plant the plant code, or null
     * @param form the document, or null
     */
    public record ManagementAppliedView(String plant, UUID form) {
    }

    /**
     * The numbers the bands and the Action plan rule are made of, in force now.
     *
     * @param limit a fill-in below it needs an Action plan; null where the application declares none
     * @param excellentAbove a score above it is Excellent; null where the application declares none
     * @param goodAbove a score above it, up to the Excellent line, is Good; anything else is Poor; null likewise
     */
    public record LegendView(Integer limit, Integer excellentAbove, Integer goodAbove) {
    }

    /**
     * One number on top. "No value" is {@code null}, which a screen reads as "nothing to show", not as zero.
     *
     * @param value the number
     * @param previous the same number for the month before, or null where none applies
     * @param whole the whole the number is part of (the plants a month is asked of), or null
     * @param extra a second number the tile names, or null
     * @param atLeast true when more records exist than one request was allowed to read, so the number is "at least"
     */
    public record ManagementTile(Double value, Double previous, Double whole, Double extra, @NotNull Boolean atLeast) {
    }

    /**
     * The numbers on top. A tile that is null is not drawn.
     *
     * @param plantsSubmitted plants that sent a fill-in for the month; whole is the plants the caller may see
     * @param averageScore the average of the plants' scores of the month
     * @param plantsBelowLimit plants with a fill-in of the month that needs an Action plan; extra is how many of
     *        those fill-ins have none yet
     * @param waitingForReview fill-ins that wait for a review before the last step
     * @param waitingForTopManagement fill-ins that wait at the last step
     * @param actionPlansOpen Action plans that are not yet approved, in every month
     */
    public record ManagementTilesView(ManagementTile plantsSubmitted, ManagementTile averageScore,
            ManagementTile plantsBelowLimit, ManagementTile waitingForReview,
            ManagementTile waitingForTopManagement, ManagementTile actionPlansOpen) {
    }

    /**
     * The graphs and lists. A section that is null is not drawn.
     *
     * @param scoresByPlant the score of each plant for the month, best first
     * @param plantsOverMonths the score of each plant in each month of the span
     * @param averageByMonth the average of the plants' scores in each month of the span
     * @param actionPlansByPlant the Action plans of each plant by where they stand, in every month
     * @param fillsByMonth the fill-ins of the span by status in each month
     */
    public record ManagementChartsView(List<PlantScore> scoresByPlant, List<PlantSeries> plantsOverMonths,
            List<MonthAverage> averageByMonth, List<PlantPlans> actionPlansByPlant, List<MonthStatus> fillsByMonth) {
    }

    /**
     * One plant's score for the month.
     *
     * @param plant the plant code
     * @param label what people read
     * @param score the average of its approved fill-ins' scores that month, or null when it has none (a gap)
     * @param forms how many fill-ins the score is made of
     * @param rank 1 for the best; equal scores share a rank; null when there is no score
     * @param band {@code EXCELLENT}, {@code GOOD} or {@code POOR}; null when there is no score
     * @param bandWord the band in the words of the legend; null when there is no score
     * @param needsActionPlan whether a fill-in of the month was decided to need an Action plan (the stored result)
     * @param belowCurrentLimit whether the score is below the limit in force now; it can differ from
     *        {@code needsActionPlan} when the limit was changed after the review
     */
    public record PlantScore(@NotNull String plant, @NotNull String label, Double score, @NotNull Integer forms,
            Integer rank, String band, String bandWord, @NotNull Boolean needsActionPlan,
            @NotNull Boolean belowCurrentLimit) {
    }

    /**
     * One plant over the months.
     *
     * @param plant the plant code
     * @param label what people read
     * @param points one for each month of the span, oldest first; a month it did not report has a null score
     */
    public record PlantSeries(@NotNull String plant, @NotNull String label, @NotNull List<MonthScore> points) {
    }

    /**
     * A score in a month.
     *
     * @param month {@code yyyy-MM}
     * @param score the score, or null for a month with none (never zero)
     */
    public record MonthScore(@NotNull String month, Double score) {
    }

    /**
     * The average of the plants in a month.
     *
     * @param month {@code yyyy-MM}
     * @param average the average of the plants' scores, or null for a month in which no plant has one
     * @param plants how many plants it is made of
     */
    public record MonthAverage(@NotNull String month, Double average, @NotNull Integer plants) {
    }

    /**
     * The Action plans of one plant.
     *
     * @param plant the plant code, or empty for plans that carry none
     * @param label what people read
     * @param open plans being written or returned
     * @param sent plans sent and waiting for a decision
     * @param approved plans that were approved
     */
    public record PlantPlans(@NotNull String plant, @NotNull String label, @NotNull Long open, @NotNull Long sent,
            @NotNull Long approved) {
    }

    /**
     * The fill-ins of one month by status.
     *
     * @param month {@code yyyy-MM}
     * @param draft being written
     * @param submitted sent and waiting for a decision
     * @param returned sent back to be corrected
     * @param approved approved
     */
    public record MonthStatus(@NotNull String month, @NotNull Long draft, @NotNull Long submitted,
            @NotNull Long returned, @NotNull Long approved) {
    }
}
