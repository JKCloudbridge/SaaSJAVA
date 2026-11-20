package app.platform.inspection.internal;

import app.platform.inspection.internal.ManagementHomeViews.ManagementChartsView;
import app.platform.inspection.internal.ManagementHomeViews.MonthAverage;
import app.platform.inspection.internal.ManagementHomeViews.MonthScore;
import app.platform.inspection.internal.ManagementHomeViews.MonthStatus;
import app.platform.inspection.internal.ManagementHomeViews.PlantPlans;
import app.platform.inspection.internal.ManagementHomeViews.PlantScore;
import app.platform.inspection.internal.ManagementHomeViews.PlantSeries;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The arithmetic of the dashboard that compares the plants (MC3, ADR-0262): which fill-ins count, how a plant's score
 * for a month is made, the bands, the ranks and the gaps. Pure: it reads no record, no setting and no clock, so every
 * rule is tested without a database. It is given the header facts the record store already judged for the caller and
 * adds up nothing else; a score is a number on a header, never calculated again here.
 *
 * <p><strong>Which fill-ins count.</strong> A score counts only when its fill-in is <em>approved</em> (a score is
 * written at every approval, so one that waits at the last step has a score that can still change). A plant's score
 * for a month is the average of its approved fill-ins' scores for that month, rounded half up to two decimals; a plant
 * with none has no score, which is a gap and never a zero. An Action plan is a fill-in whose {@code parent} is set;
 * it is never part of a score, a tile of the month or the months' charts.
 *
 * <p><strong>The bands.</strong> A score above {@code excellentAbove} is Excellent, above {@code goodAbove} up to that
 * line is Good, and anything else is Poor, so exactly 85 is Good and exactly 65 is Poor with the defaults. The limit of
 * the Action plan rule is another number and is never used to word a band.
 */
final class ManagementNumbers {

    static final String EXCELLENT = "EXCELLENT";
    static final String GOOD = "GOOD";
    static final String POOR = "POOR";

    private static final String DRAFT = "DRAFT";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String RETURNED = "RETURNED";
    private static final String APPROVED = "APPROVED";
    private static final String ACTION_PLAN = "ACTION_PLAN";

    private ManagementNumbers() {
    }

    /**
     * What one fill-in header says.
     *
     * @param id the fill-in
     * @param templateId the document it was started on, as the text of its identifier
     * @param name the name it carries
     * @param plant the plant code, or null
     * @param period {@code yyyy-MM}, or null
     * @param score the score in percent points, or null
     * @param result {@code PASS}, {@code ACTION_PLAN} or null
     * @param status {@code DRAFT}, {@code SUBMITTED}, {@code RETURNED} or {@code APPROVED}
     * @param parent the fill-in an Action plan answers, or null for every other fill-in
     * @param scoredOn the day (UTC) the reviewer's Submit stamped the score, or null (ADR-0266)
     * @param planSentOn the day (UTC) the plant head first sent an Action plan, or null
     * @param sentAt when the header last changed, which for a fill-in that waits is when it was sent; or null
     */
    record Row(UUID id, String templateId, String name, String plant, String period, BigDecimal score, String result,
            String status, UUID parent, String scoredOn, String planSentOn, java.time.Instant sentAt) {

        /** A row that carries the two days and no time. */
        Row(UUID id, String templateId, String name, String plant, String period, BigDecimal score, String result,
                String status, UUID parent, String scoredOn, String planSentOn) {
            this(id, templateId, name, plant, period, score, result, status, parent, scoredOn, planSentOn, null);
        }

        /** A row that carries neither day (what the other option reads). */
        Row(UUID id, String templateId, String name, String plant, String period, BigDecimal score, String result,
                String status, UUID parent) {
            this(id, templateId, name, plant, period, score, result, status, parent, null, null, null);
        }

        boolean isPlan() {
            return parent != null;
        }

        /** Sent for a decision at some time: waiting or decided (a draft and a returned one are not). */
        boolean sent() {
            return SUBMITTED.equals(status) || APPROVED.equals(status);
        }

        /** Whether its score counts (approved, has a score, belongs to a plant and a month). */
        boolean scored() {
            return !isPlan() && APPROVED.equals(status) && score != null && plant != null && period != null;
        }

        /** Whether it was decided that it needs an Action plan (the stored result, of a fill-in that was sent). */
        boolean needsPlan() {
            return !isPlan() && sent() && ACTION_PLAN.equals(result);
        }

        /** Prints the identity only: the values are the organization's. */
        @Override
        public String toString() {
            return "Row[" + id + "]";
        }
    }

    /**
     * The numbers in force.
     *
     * @param limit below it a fill-in needs an Action plan, or null
     * @param excellentAbove above it a score is Excellent, or null
     * @param goodAbove above it, up to the Excellent line, a score is Good, or null
     */
    record Bands(Integer limit, Integer excellentAbove, Integer goodAbove) {

        /** The band of a score, or null when the bands are not declared. */
        String bandOf(double score) {
            if (excellentAbove == null || goodAbove == null) {
                return null;
            }
            return score > excellentAbove ? EXCELLENT : score > goodAbove ? GOOD : POOR;
        }

        /** The word of a band as the legend says it. */
        static String word(String band) {
            if (band == null) {
                return null;
            }
            return switch (band) {
                case EXCELLENT -> "Excellent";
                case GOOD -> "Good";
                default -> "Poor";
            };
        }
    }

    // ---- scores ----

    /** The average of some scores, rounded half up to two decimals; null when there are none. */
    static Double average(Collection<BigDecimal> scores) {
        if (scores.isEmpty()) {
            return null;
        }
        BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP).doubleValue();
    }

    /** The plants' scores for one month: the average of each plant's approved fill-ins; plants with none are absent. */
    static Map<String, Double> plantScores(Collection<Row> rows, String month) {
        Map<String, List<BigDecimal>> byPlant = new TreeMap<>();
        for (Row row : rows) {
            if (row.scored() && month.equals(row.period())) {
                byPlant.computeIfAbsent(row.plant(), key -> new ArrayList<>()).add(row.score());
            }
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        byPlant.forEach((plant, list) -> scores.put(plant, average(list)));
        return scores;
    }

    /** The average over the plants of their scores for the month; null when no plant has one. */
    static Double averageOfPlants(Collection<Row> rows, String month) {
        Collection<Double> scores = plantScores(rows, month).values();
        if (scores.isEmpty()) {
            return null;
        }
        return average(scores.stream().map(BigDecimal::valueOf).toList());
    }

    /** The plants that sent a fill-in (waiting or decided) for the month. */
    static Set<String> plantsSubmitted(Collection<Row> rows, String month) {
        return rows.stream().filter(row -> !row.isPlan() && row.sent() && row.plant() != null
                && month.equals(row.period())).map(Row::plant).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The fill-ins of the month that were decided to need an Action plan. */
    static List<Row> needingPlan(Collection<Row> rows, String month) {
        return rows.stream().filter(row -> row.needsPlan() && row.plant() != null && month.equals(row.period()))
                .toList();
    }

    /** How many of those have no Action plan yet: no Action plan names them. */
    static long withoutPlan(Collection<Row> needing, Set<UUID> answered) {
        return needing.stream().filter(row -> !answered.contains(row.id())).count();
    }

    // ---- the charts ----

    /**
     * The score of each plant for the month, best first, a plant with no score last. Every plant given in {@code
     * labels} is there, and any plant that has a score and is not in it.
     */
    static List<PlantScore> scoresByPlant(Collection<Row> rows, String month, Map<String, String> labels,
            Bands bands) {
        Map<String, Double> scores = plantScores(rows, month);
        Map<String, Long> forms = new HashMap<>();
        rows.stream().filter(row -> row.scored() && month.equals(row.period()))
                .forEach(row -> forms.merge(row.plant(), 1L, Long::sum));
        Set<String> needing = needingPlan(rows, month).stream().map(Row::plant).collect(Collectors.toSet());
        Set<String> plants = new TreeSet<>(labels.keySet());
        plants.addAll(scores.keySet());
        List<PlantScore> list = new ArrayList<>();
        for (String plant : plants) {
            Double score = scores.get(plant);
            String band = score == null ? null : bands.bandOf(score);
            list.add(new PlantScore(plant, labels.getOrDefault(plant, plant), score,
                    forms.getOrDefault(plant, 0L).intValue(), null, band, Bands.word(band), needing.contains(plant),
                    score != null && bands.limit() != null && score < bands.limit()));
        }
        list.sort(Comparator.comparing(PlantScore::score, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PlantScore::label).thenComparing(PlantScore::plant));
        List<PlantScore> ranked = new ArrayList<>();
        Double before = null;
        int rank = 0;
        for (int index = 0; index < list.size(); index++) {
            PlantScore one = list.get(index);
            if (one.score() != null && (before == null || !before.equals(one.score()))) {
                rank = index + 1;
                before = one.score();
            }
            ranked.add(new PlantScore(one.plant(), one.label(), one.score(), one.forms(),
                    one.score() == null ? null : rank, one.band(), one.bandWord(), one.needsActionPlan(),
                    one.belowCurrentLimit()));
        }
        return ranked;
    }

    /** One series for each plant that has a score in some month of the span; a month with none is a gap. */
    static List<PlantSeries> plantsOverMonths(Collection<Row> rows, List<String> months, Map<String, String> labels) {
        Map<String, Map<String, Double>> byMonth = new LinkedHashMap<>();
        for (String month : months) {
            byMonth.put(month, plantScores(rows, month));
        }
        Set<String> plants = new TreeSet<>();
        byMonth.values().forEach(scores -> plants.addAll(scores.keySet()));
        List<PlantSeries> series = new ArrayList<>();
        for (String plant : plants) {
            List<MonthScore> points = new ArrayList<>();
            for (String month : months) {
                points.add(new MonthScore(month, byMonth.get(month).get(plant)));
            }
            series.add(new PlantSeries(plant, labels.getOrDefault(plant, plant), points));
        }
        series.sort(Comparator.comparing(PlantSeries::label).thenComparing(PlantSeries::plant));
        return series;
    }

    /** The average of the plants in each month of the span; a month with no score has a null average. */
    static List<MonthAverage> averageByMonth(Collection<Row> rows, List<String> months) {
        return months.stream().map(month -> new MonthAverage(month, averageOfPlants(rows, month),
                plantScores(rows, month).size())).toList();
    }

    /** The fill-ins of each month of the span by status; Action plans are not counted. */
    static List<MonthStatus> fillsByMonth(Collection<Row> rows, List<String> months) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        months.forEach(month -> counts.put(month, new long[4]));
        for (Row row : rows) {
            long[] slot = row.isPlan() || row.period() == null ? null : counts.get(row.period());
            if (slot == null) {
                continue;
            }
            switch (row.status()) {
                case DRAFT -> slot[0]++;
                case SUBMITTED -> slot[1]++;
                case RETURNED -> slot[2]++;
                case APPROVED -> slot[3]++;
                default -> { }
            }
        }
        List<MonthStatus> list = new ArrayList<>();
        counts.forEach((month, slot) -> list.add(new MonthStatus(month, slot[0], slot[1], slot[2], slot[3])));
        return list;
    }

    /**
     * The Action plans of each plant by where they stand, and the ones still owed: the fill-ins of {@code fills} that
     * need an Action plan and that no Action plan of {@code plans} answers (2026-11-17). Rows of {@code plans} that are
     * not Action plans are ignored; a plant that only owes one is listed too.
     */
    static List<PlantPlans> plansByPlant(Collection<Row> plans, Collection<Row> fills, Map<String, String> labels,
            String notRecorded) {
        Map<String, long[]> counts = new TreeMap<>();
        for (Row row : plans) {
            if (!row.isPlan()) {
                continue;
            }
            long[] slot = counts.computeIfAbsent(row.plant() == null ? "" : row.plant(), key -> new long[4]);
            switch (row.status()) {
                case SUBMITTED -> slot[1]++;
                case APPROVED -> slot[2]++;
                default -> slot[0]++;
            }
        }
        Set<UUID> answered = answered(plans);
        for (Row row : fills) {
            if (!row.isPlan() && row.needsPlan() && row.plant() != null && !answered.contains(row.id())) {
                counts.computeIfAbsent(row.plant(), key -> new long[4])[3]++;
            }
        }
        List<PlantPlans> list = new ArrayList<>();
        counts.forEach((plant, slot) -> list.add(new PlantPlans(plant, plant.isEmpty() ? notRecorded
                : labels.getOrDefault(plant, plant), slot[0], slot[1], slot[2], slot[3])));
        list.sort(Comparator.comparing(PlantPlans::label).thenComparing(PlantPlans::plant));
        return list;
    }

    /** Action plans that are not yet approved. */
    static long open(Collection<Row> plans) {
        return plans.stream().filter(row -> row.isPlan() && !APPROVED.equals(row.status())).count();
    }

    /** The fill-ins the Action plans answer. */
    static Set<UUID> answered(Collection<Row> plans) {
        Set<UUID> ids = new HashSet<>();
        plans.stream().filter(Row::isPlan).forEach(row -> ids.add(row.parent()));
        return ids;
    }

    // ---- months ----

    /** The {@code count} months that end with {@code last}, oldest first, as {@code yyyy-MM}. */
    static List<String> monthsEnding(YearMonth last, int count) {
        List<String> months = new ArrayList<>();
        for (int back = count - 1; back >= 0; back--) {
            months.add(last.minusMonths(back).toString());
        }
        return months;
    }

    /** An empty set of charts: what a caller who may read no fill-in is given. */
    static ManagementChartsView noCharts() {
        return new ManagementChartsView(null, null, null, null, null);
    }
}
