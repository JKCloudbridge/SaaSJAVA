package app.platform.inspection.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the home page of the Quality Management System sends and receives (FA9, ADR-0203). The views live here and not
 * in the API contract module because they carry the cluster's words; the OpenAPI document and the generated client
 * carry them all the same.
 *
 * <p>Everything is for a screen to draw, never to decide. A section that is null is one the caller may not see at all
 * (the object behind it cannot be read): the screen draws nothing for it, not a zero. A section that failed is named
 * in {@code problems}, so one tile can fail without the page failing. No view carries a cell, a typed text or an
 * identifier of a person.
 */
public final class QualityHomeViews {

    private QualityHomeViews() {
    }

    /**
     * The question: a period, and optionally one location and one department. Typed text never goes in an address, so
     * this is a body.
     *
     * @param period {@code 7d}, {@code 30d}, {@code 90d} or {@code 12m}; empty means {@code 30d}
     * @param location a location code the organization offers, or null for all
     * @param department a department code the organization offers, or null for all
     */
    public record SummaryRequest(String period, String location, String department) {

        /** Prints nothing typed. */
        @Override
        public String toString() {
            return "SummaryRequest[redacted]";
        }
    }

    /**
     * The answer.
     *
     * @param generatedAt when it was worked out
     * @param period the days it covers and the days it is compared with
     * @param filters what the filter bar offers: the locations and departments the organization keeps
     * @param applied the filters that were applied (after checking them against what is offered)
     * @param tiles the numbers on top
     * @param charts the graphs and lists below
     * @param problems the sections that could not be worked out, by name; empty when all were
     */
    public record SummaryView(@NotNull Instant generatedAt, @NotNull PeriodView period, @NotNull FiltersView filters,
            @NotNull AppliedView applied, @NotNull TilesView tiles, @NotNull ChartsView charts,
            @NotNull List<String> problems) {
    }

    /**
     * The days of the period.
     *
     * @param key the period
     * @param from the start of its first day (UTC)
     * @param to the start of the day after its last (UTC)
     * @param previousFrom the start of the period before it
     * @param previousTo the end of the period before it
     */
    public record PeriodView(@NotNull String key, @NotNull Instant from, @NotNull Instant to,
            @NotNull Instant previousFrom, @NotNull Instant previousTo) {
    }

    /**
     * The filter bar's choices.
     *
     * @param locations the locations of the organization's list, each with the departments that go with it
     */
    public record FiltersView(@NotNull List<LocationOption> locations) {
    }

    /**
     * One location.
     *
     * @param code the code to send back
     * @param label what people read
     * @param departments the departments that go with it
     */
    public record LocationOption(@NotNull String code, @NotNull String label,
            @NotNull List<DepartmentOption> departments) {
    }

    /**
     * One department.
     *
     * @param code the code to send back
     * @param label what people read
     */
    public record DepartmentOption(@NotNull String code, @NotNull String label) {
    }

    /**
     * The filters that were applied.
     *
     * @param location the location code, or null
     * @param department the department code, or null
     */
    public record AppliedView(String location, String department) {
    }

    /**
     * One number on top. A number is never negative; "no value" is {@code null}, which a screen reads as "nothing to
     * show in this period", not as zero.
     *
     * @param value the number (a count, days or a percentage by the tile), or null
     * @param previous the same number for the period before, or null where no period applies
     * @param extra a second number the tile names (the age of the oldest, in days), or null
     * @param atLeast true when more records exist than the page was allowed to read, so the number is "at least"
     * @param byLocation whether the location filter changes this number
     * @param byDepartment whether the department filter changes this number
     */
    public record Tile(Double value, Double previous, Double extra, @NotNull Boolean atLeast,
            @NotNull Boolean byLocation, @NotNull Boolean byDepartment) {
    }

    /**
     * The numbers on top. A tile that is null is not drawn.
     *
     * @param activeTemplates templates in use
     * @param waitingApproval templates in review; extra is the age of the oldest in days
     * @param openChangeRequests change requests that are open or accepted, as the change request tab counts them
     * @param fillsSubmitted fill-ins sent in the period
     * @param fillsWaiting fill-ins that wait for a review; null where the organization has no process for fill-ins
     * @param templatesPublished templates published or revised in the period
     * @param averageApprovalDays days from sending a template to its decision, on average
     * @param rejectedShare the share of decided templates that were rejected, in percent
     */
    public record TilesView(Tile activeTemplates, Tile waitingApproval, Tile openChangeRequests, Tile fillsSubmitted,
            Tile fillsWaiting, Tile templatesPublished, Tile averageApprovalDays, Tile rejectedShare) {
    }

    /**
     * The graphs and lists. A section that is null is not drawn.
     *
     * @param fillsPerDay the fill-ins sent on each day of the period
     * @param templatesByStatus the templates by where they are in their life
     * @param templatesByLevelAndType the templates by level and document type
     * @param fillsByTemplate the ten templates with the most fill-ins in the period
     * @param fillsByDepartment the fill-ins of the period by department
     * @param approvalStepDays the days an approval spends at each step, on average
     * @param publishedPerMonth the templates published or revised in each of the last twelve months
     * @param recentActivity the latest things that happened, newest first
     */
    public record ChartsView(DaysView fillsPerDay, List<StatusCount> templatesByStatus,
            List<LevelTypeCount> templatesByLevelAndType, List<TemplateCount> fillsByTemplate,
            List<DepartmentCount> fillsByDepartment, List<StepDays> approvalStepDays,
            List<MonthCount> publishedPerMonth, List<Activity> recentActivity) {
    }

    /**
     * A count for each day.
     *
     * @param total the sum of the days
     * @param days every day of the period, oldest first, those with nothing included
     * @param atLeast true when more records exist than were read
     */
    public record DaysView(@NotNull Long total, @NotNull List<DayCount> days, @NotNull Boolean atLeast) {
    }

    /**
     * One day.
     *
     * @param day {@code yyyy-MM-dd}
     * @param count how many
     */
    public record DayCount(@NotNull String day, @NotNull Long count) {
    }

    /**
     * Templates in one state.
     *
     * @param key {@code DRAFT}, {@code IN_REVIEW}, {@code APPROVED}, {@code ACTIVE}, {@code RETIRED} or
     *        {@code NO_SHEET}
     * @param label what people read
     * @param count how many
     */
    public record StatusCount(@NotNull String key, @NotNull String label, @NotNull Long count) {
    }

    /**
     * Templates of one level and one document type.
     *
     * @param level the level code, or empty when none is set
     * @param levelLabel what people read
     * @param type the document type code, or empty
     * @param typeLabel what people read
     * @param count how many
     */
    public record LevelTypeCount(@NotNull String level, @NotNull String levelLabel, @NotNull String type,
            @NotNull String typeLabel, @NotNull Long count) {
    }

    /**
     * Fill-ins of one template.
     *
     * @param templateId the template
     * @param name the name the fill-ins carry (that of the template when they were started)
     * @param count how many
     */
    public record TemplateCount(@NotNull UUID templateId, @NotNull String name, @NotNull Long count) {
    }

    /**
     * Fill-ins of one department.
     *
     * @param location the location code, or null for "not recorded"
     * @param department the department code, or null for "not recorded"
     * @param label what people read
     * @param count how many
     */
    public record DepartmentCount(String location, String department, @NotNull String label, @NotNull Long count) {
    }

    /**
     * The days an approval spends at one step.
     *
     * @param position the step, from 1
     * @param name the step's name as the process gives it
     * @param averageDays the average days from the step before (or from sending) to this step's approval
     * @param samples how many approvals it is made of
     */
    public record StepDays(@NotNull Integer position, @NotNull String name, @NotNull Double averageDays,
            @NotNull Long samples) {
    }

    /**
     * Templates published in one month.
     *
     * @param month {@code yyyy-MM}
     * @param count how many
     */
    public record MonthCount(@NotNull String month, @NotNull Long count) {
    }

    /**
     * One thing that happened.
     *
     * @param kind {@code PUBLISHED}, {@code REVISED}, {@code UNPUBLISHED}, {@code RETIRED} or {@code SUBMITTED}
     * @param at when
     * @param who the display name of the person, or null when they are no longer a member
     * @param about {@code template} or {@code fill-in}
     * @param id the template or fill-in
     * @param name the name of the template or fill-in
     */
    public record Activity(@NotNull String kind, @NotNull Instant at, String who, @NotNull String about,
            @NotNull UUID id, @NotNull String name) {
    }
}
