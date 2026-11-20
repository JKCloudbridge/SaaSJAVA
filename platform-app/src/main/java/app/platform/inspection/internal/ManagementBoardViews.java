package app.platform.inspection.internal;

import app.platform.inspection.internal.ManagementHomeViews.ManagementAppliedView;
import app.platform.inspection.internal.ManagementHomeViews.ManagementFiltersView;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the plant board of the Management Core Home page sends (ADR-0266, "Option 1"). The question is the one of the
 * other option ({@link ManagementHomeViews.ManagementSummaryRequest}). Every status and every word is decided on the
 * server from the stamped days, the clock of the platform and the settings in force, so the screen draws and decides
 * nothing. Every name starts with {@code Management}: the contract generator makes one schema of two classes with the
 * same simple name.
 *
 * <p>A section that is null is one the caller may not see at all. A score that does not exist is {@code null}, never
 * zero. Scores, plants, months and days are the organization's data and never reach a log.
 */
public final class ManagementBoardViews {

    private ManagementBoardViews() {
    }

    /**
     * The answer.
     *
     * @param generatedAt when it was worked out
     * @param month the month the page was asked about ({@code yyyy-MM}, UTC)
     * @param today the day statuses were judged on ({@code yyyy-MM-dd}, UTC), from the platform's clock
     * @param limit the score below which an Action plan is needed, or null where the application declares none
     * @param dueDays the days a plant head has to send an Action plan, or null where the application declares none
     * @param months the months of the chart, the tiles and the grid that have ended, oldest first, without leading
     *        months in which nothing was recorded
     * @param currentMonth the month in progress when it is part of the question (the last column of the grid), or
     *        null
     * @param monthChoices the months a person may choose, newest first
     * @param filters what the filter bar offers
     * @param applied the filters that were applied
     * @param atLeast true when more records exist than one request may read, so numbers are "at least"
     * @param plants one for each plant shown: the tile and the chart line
     * @param grid one row for each plant shown, the cells of the tracker
     * @param plans the Action plans owed and sent, for the detail table, most urgent first
     * @param totals the three numbers on top of the tracker
     * @param pending the fill-ins sent and waiting for their reviewer, longest waiting first
     * @param problems the sections that could not be worked out, by name
     */
    public record ManagementPlantBoard(@NotNull Instant generatedAt, @NotNull String month, @NotNull String today,
            Integer limit, Integer dueDays, @NotNull List<String> months, String currentMonth,
            @NotNull List<String> monthChoices,
            @NotNull ManagementFiltersView filters, @NotNull ManagementAppliedView applied,
            @NotNull Boolean atLeast, List<ManagementBoardPlant> plants, List<ManagementBoardGridRow> grid,
            List<ManagementBoardPlan> plans, ManagementBoardTotals totals, List<ManagementBoardPending> pending,
            @NotNull List<String> problems) {
    }

    /**
     * One plant's tile and chart line.
     *
     * @param plant the plant code
     * @param label what people read
     * @param colour the plant's place in the colours, from 1: a plant keeps it in every widget
     * @param latestMonth the last month of {@code months}, or null when there is none
     * @param latestScore the plant's score in that month, or null (a gap)
     * @param change the latest score minus the score of the earlier month named in {@code changeMonth}, or null
     * @param changeMonth the nearest earlier month that has a score, or null
     * @param points one for each of the months, oldest first; null for a month with no score
     * @param scoresSubmitted months with an approved score
     * @param scoresOf months that have ended
     * @param plansSent Action plans sent to the plant head (a score below the limit with a day stamped)
     * @param plansSubmitted of those, the ones the plant head sent, on time or late
     * @param overdue plans not sent although the due day has passed
     */
    public record ManagementBoardPlant(@NotNull String plant, @NotNull String label, @NotNull Integer colour,
            String latestMonth, Double latestScore, Double change, String changeMonth,
            @NotNull List<Double> points, @NotNull Integer scoresSubmitted, @NotNull Integer scoresOf,
            @NotNull Integer plansSent, @NotNull Integer plansSubmitted, @NotNull Integer overdue) {
    }

    /**
     * One plant's row of the tracker grid.
     *
     * @param plant the plant code
     * @param label what people read
     * @param colour the plant's colour place, as in the tile
     * @param cells one for each of the months, oldest first
     * @param current the cell of the month in progress, or null when the question has none
     */
    public record ManagementBoardGridRow(@NotNull String plant, @NotNull String label, @NotNull Integer colour,
            @NotNull List<ManagementBoardCell> cells, ManagementBoardCell current) {
    }

    /**
     * One cell of the grid.
     *
     * @param month {@code yyyy-MM}
     * @param score the plant's score, or null
     * @param status {@code NO_PLAN}, {@code NO_SCORE}, {@code AWAITING}, {@code SUBMITTED}, {@code LATE},
     *        {@code OVERDUE}, {@code NO_DATE}, {@code IN_REVIEW} or {@code NOT_SENT}
     * @param word the one word drawn under the score
     * @param fillId a fill-in the cell is about, which a person who sees the cell may open, or null
     */
    public record ManagementBoardCell(@NotNull String month, Double score, @NotNull String status,
            @NotNull String word, UUID fillId) {
    }

    /**
     * One Action plan of the detail table.
     *
     * @param plant the plant code
     * @param label what people read
     * @param colour the plant's colour place
     * @param month the month of the score ({@code yyyy-MM})
     * @param score the fill-in's score
     * @param sentOn the day the plant head was told ({@code yyyy-MM-dd}), or null where it was not stamped
     * @param dueOn the day it is due, or null
     * @param submittedOn the day the plant head sent the plan, or null
     * @param status {@code AWAITING}, {@code SUBMITTED}, {@code LATE}, {@code OVERDUE} or {@code NO_DATE}
     * @param statusText the status in words, such as {@code Overdue 127 days}
     * @param overdueDays whole days after the due day for an overdue plan, else null
     * @param fillId the fill-in the plan is for
     * @param planId the Action plan, or null while none was started
     */
    public record ManagementBoardPlan(@NotNull String plant, @NotNull String label, @NotNull Integer colour,
            @NotNull String month, Double score, String sentOn, String dueOn, String submittedOn,
            @NotNull String status, @NotNull String statusText, Integer overdueDays, @NotNull UUID fillId,
            UUID planId) {
    }

    /**
     * The three numbers on top of the tracker, over the plants shown.
     *
     * @param plansSent plans sent to plant heads
     * @param plansSubmitted of those, sent by the plant head
     * @param overdue plans not sent past their due day
     */
    public record ManagementBoardTotals(@NotNull Integer plansSent, @NotNull Integer plansSubmitted,
            @NotNull Integer overdue) {
    }

    /**
     * One fill-in that was sent and waits for its reviewer's decision. No reviewer is named: the person who may decide
     * opens the fill-in.
     *
     * @param plant the plant code
     * @param label what people read
     * @param colour the plant's colour place
     * @param month the month of the fill-in ({@code yyyy-MM})
     * @param name the name the fill-in carries
     * @param score its score when it has one, else null
     * @param sentOn the day it was sent ({@code yyyy-MM-dd}, UTC), or null
     * @param daysWaiting whole days since it was sent, or null
     * @param actionPlan whether it is an Action plan
     * @param fillId the fill-in, which a person who sees this row may open
     */
    public record ManagementBoardPending(@NotNull String plant, @NotNull String label, @NotNull Integer colour,
            @NotNull String month, @NotNull String name, Double score, String sentOn, Integer daysWaiting,
            @NotNull Boolean actionPlan, @NotNull UUID fillId) {
    }
}
