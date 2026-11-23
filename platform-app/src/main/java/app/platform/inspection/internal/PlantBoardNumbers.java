package app.platform.inspection.internal;

import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardCell;
import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardGridRow;
import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardPlan;
import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardPlant;
import app.platform.inspection.internal.ManagementBoardViews.ManagementBoardTotals;
import app.platform.inspection.internal.ManagementNumbers.Row;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The arithmetic of the plant board (ADR-0266, "Option 1"): the status of an Action plan, the cells of the grid, the
 * tiles and the rows of the detail table. Pure: it reads no record, no setting and no clock, it is handed the header
 * facts the record store already judged for the caller, the days stamped by the platform and today as a day, so every
 * rule is tested without a database. It never calculates a score and never repeats the Action plan rule: whether a
 * fill-in needs a plan is the stored result {@link FillScores} wrote. Days are days in UTC.
 *
 * <p><strong>The rule of one plan.</strong> A fill-in that was approved with the result {@code ACTION_PLAN} is told to
 * its plant head on the day its score was stamped; the plant head has {@code dueDays} days from then. A plan sent on
 * the due day is on time. A day that was never stamped (a fill-in older than the stamps) is never guessed: the plan is
 * {@code NO_DATE}, drawn with a dash and left out of the sent, on-time and overdue counts.
 */
final class PlantBoardNumbers {

    /** Where a cell or a plan stands. */
    enum Status {
        NO_PLAN("Passed"),
        NO_SCORE("No score"),
        AWAITING("Awaiting"),
        SUBMITTED("Submitted"),
        LATE("Late"),
        OVERDUE("Overdue"),
        NO_DATE("No date"),
        IN_REVIEW("In review"),
        NOT_SENT("Awaiting");

        private final String word;

        Status(String word) {
            this.word = word;
        }

        /** The one word drawn under a score. */
        String word() {
            return word;
        }

        /** How urgent a plan is: lower is more urgent. */
        private int urgency() {
            return switch (this) {
                case OVERDUE -> 0;
                case AWAITING -> 1;
                case NO_DATE -> 2;
                case LATE -> 3;
                default -> 4;
            };
        }
    }

    /**
     * The judgement of one plan.
     *
     * @param status where it stands
     * @param scoredOn the day the plant head was told, or null
     * @param dueOn the day it is due, or null
     * @param sentOn the day the plant head sent it, or null
     * @param overdueDays whole days after the due day, only for an overdue plan
     */
    record Judgement(Status status, LocalDate scoredOn, LocalDate dueOn, LocalDate sentOn, Integer overdueDays) {

        /** The status in the words of the detail table. */
        String text() {
            return switch (status) {
                case AWAITING -> "Awaiting plant head";
                case SUBMITTED -> "Submitted";
                case LATE -> "Submitted late";
                case OVERDUE -> "Overdue " + overdueDays + (overdueDays == 1 ? " day" : " days");
                default -> status.word();
            };
        }
    }

    /**
     * What the board is made of.
     *
     * @param months the months that have ended, oldest first, already without leading empty months
     * @param current the month in progress that is part of the question, or null
     * @param plants the plants shown, in order, code to label
     * @param colours the colour place of every plant the caller may see, from 1
     * @param fills the fill-ins (never Action plans) of the months, filtered by the plant and form chosen
     * @param plans the Action plans the caller may see
     * @param dueDays the days to send a plan, or null
     * @param today the day judged on
     */
    record Inputs(List<String> months, String current, Map<String, String> plants, Map<String, Integer> colours,
            List<Row> fills, List<Row> plans, Integer dueDays, LocalDate today) {
    }

    /** What the board is. */
    record Board(List<ManagementBoardPlant> plants, List<ManagementBoardGridRow> grid,
            List<ManagementBoardPlan> plans, ManagementBoardTotals totals,
            ManagementBoardViews.ManagementBoardKpis kpis) {
    }

    private PlantBoardNumbers() {
    }

    // ---- one plan ----

    /** The day written as {@code yyyy-MM-dd}, or null when it is not one: a day that is not there is never guessed. */
    static LocalDate day(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException notADay) {
            return null;
        }
    }

    /**
     * Judges the plan of a fill-in that needs one.
     *
     * @param fill the approved fill-in whose result says an Action plan is needed
     * @param plan the Action plan that answers it, or null while none was started
     * @param dueDays the days to send it, or null
     * @param today the day judged on
     */
    static Judgement judge(Row fill, Row plan, Integer dueDays, LocalDate today) {
        LocalDate scored = day(fill.scoredOn());
        if (scored == null || dueDays == null) {
            return new Judgement(Status.NO_DATE, scored, null, null, null);
        }
        LocalDate due = scored.plusDays(dueDays);
        LocalDate sent = plan == null ? null : day(plan.planSentOn());
        if (sent != null) {
            return new Judgement(sent.isAfter(due) ? Status.LATE : Status.SUBMITTED, scored, due, sent, null);
        }
        if (plan != null && ("SUBMITTED".equals(plan.status()) || "APPROVED".equals(plan.status()))) {
            // Sent before the day was stamped: never guess it.
            return new Judgement(Status.NO_DATE, scored, due, null, null);
        }
        if (today.isAfter(due)) {
            return new Judgement(Status.OVERDUE, scored, due, null, (int) ChronoUnit.DAYS.between(due, today));
        }
        return new Judgement(Status.AWAITING, scored, due, null, null);
    }

    /** The months that have ended, without the leading months in which no fill-in was recorded at all. */
    static List<String> trimmed(List<String> ended, List<Row> fills) {
        java.util.Set<String> recorded = new java.util.HashSet<>();
        fills.stream().filter(row -> row.period() != null).forEach(row -> recorded.add(row.period()));
        for (int at = 0; at < ended.size(); at++) {
            if (recorded.contains(ended.get(at))) {
                return ended.subList(at, ended.size());
            }
        }
        return List.of();
    }

    /** The fill-ins and Action plans that were sent and wait for a decision, longest waiting first. */
    static List<ManagementBoardViews.ManagementBoardPending> pending(List<Row> rows, Map<String, String> plants,
            Map<String, Integer> colours, LocalDate today) {
        return rows.stream().filter(row -> "SUBMITTED".equals(row.status()) && row.plant() != null
                && row.period() != null && plants.containsKey(row.plant()))
                .map(row -> {
                    LocalDate sent = row.sentAt() == null ? null
                            : row.sentAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
                    return new ManagementBoardViews.ManagementBoardPending(row.plant(), plants.get(row.plant()),
                            colours.getOrDefault(row.plant(), 1), row.period(), row.name() == null ? "" : row.name(),
                            row.score() == null ? null : row.score().doubleValue(),
                            sent == null ? null : sent.toString(),
                            sent == null ? null : (int) ChronoUnit.DAYS.between(sent, today), row.isPlan(),
                            row.id());
                })
                .sorted(Comparator.comparing(ManagementBoardViews.ManagementBoardPending::daysWaiting,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ManagementBoardViews.ManagementBoardPending::label)
                        .thenComparing(ManagementBoardViews.ManagementBoardPending::fillId))
                .toList();
    }

    // ---- the board ----

    /** Works the board out. */
    static Board build(Inputs in) {
        Map<UUID, Row> planOf = new HashMap<>();
        in.plans().stream().filter(Row::isPlan).forEach(row -> planOf.putIfAbsent(row.parent(), row));
        Map<String, Map<String, Double>> scores = new LinkedHashMap<>();
        List<String> shown = new ArrayList<>(in.months());
        if (in.current() != null && !shown.contains(in.current())) {
            shown.add(in.current());
        }
        shown.forEach(month -> scores.put(month, ManagementNumbers.plantScores(in.fills(), month)));

        List<ManagementBoardPlant> tiles = new ArrayList<>();
        List<ManagementBoardGridRow> grid = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, String> plant : in.plants().entrySet()) {
            String code = plant.getKey();
            int colour = in.colours().getOrDefault(code, 1);
            List<ManagementBoardCell> cells = new ArrayList<>();
            List<Double> points = new ArrayList<>();
            for (String month : in.months()) {
                cells.add(cell(in, planOf, scores, code, month, month.equals(in.current())));
                points.add(scores.get(month).get(code));
            }
            ManagementBoardCell current = in.current() == null || in.months().contains(in.current()) ? null
                    : cell(in, planOf, scores, code, in.current(), true);
            grid.add(new ManagementBoardGridRow(code, plant.getValue(), colour, cells, current));

            List<Entry> mine = new ArrayList<>();
            for (String month : shown) {
                for (Row fill : needing(in.fills(), code, month)) {
                    Judgement judgement = judge(fill, planOf.get(fill.id()), in.dueDays(), in.today());
                    mine.add(new Entry(code, plant.getValue(), colour, month, fill, planOf.get(fill.id()),
                            judgement));
                }
            }
            entries.addAll(mine);
            tiles.add(tile(code, plant.getValue(), colour, in.months(), points, mine, in.current()));
        }
        entries.sort(Comparator.comparingInt((Entry entry) -> entry.judgement().status().urgency())
                .thenComparing(Entry::month, Comparator.reverseOrder()).thenComparing(Entry::label)
                .thenComparing(entry -> entry.fill().id()));
        List<ManagementBoardPlan> plans = entries.stream().map(PlantBoardNumbers::planView).toList();
        return new Board(tiles, grid, plans, new ManagementBoardTotals(sent(entries), submitted(entries),
                overdue(entries)), kpis(in, entries, scores));
    }

    /** The four numbers over the plants shown (ADR-0266). */
    private static ManagementBoardViews.ManagementBoardKpis kpis(Inputs in, List<Entry> entries,
            Map<String, Map<String, Double>> scores) {
        List<Long> days = new ArrayList<>();
        int onTime = 0;
        for (Entry entry : entries) {
            Status status = entry.judgement().status();
            if ((status == Status.SUBMITTED || status == Status.LATE) && entry.judgement().scoredOn() != null
                    && entry.judgement().sentOn() != null) {
                days.add(ChronoUnit.DAYS.between(entry.judgement().scoredOn(), entry.judgement().sentOn()));
                if (status == Status.SUBMITTED) {
                    onTime++;
                }
            }
        }
        Double average = days.isEmpty() ? null : BigDecimal.valueOf(days.stream().mapToLong(Long::longValue).sum())
                .divide(BigDecimal.valueOf(days.size()), 1, java.math.RoundingMode.HALF_UP).doubleValue();
        Integer percent = days.isEmpty() ? null : (int) Math.round(onTime * 100.0 / days.size());

        String month = in.current() != null ? in.current()
                : in.months().isEmpty() ? null : in.months().get(in.months().size() - 1);
        int sent = month == null ? 0 : (int) in.plants().keySet().stream().filter(plant -> in.fills().stream()
                .anyMatch(row -> plant.equals(row.plant()) && month.equals(row.period()) && row.sent())).count();

        Integer streak = in.months().isEmpty() ? null : 0;
        String streakPlant = null;
        for (String plant : in.plants().keySet()) {
            int run = 0;
            for (int at = in.months().size() - 1; at >= 0; at--) {
                String each = in.months().get(at);
                boolean passed = scores.get(each).get(plant) != null && needing(in.fills(), plant, each).isEmpty();
                if (!passed) {
                    break;
                }
                run++;
            }
            if (streak != null && run > streak) {
                streak = run;
                streakPlant = in.plants().get(plant);
            }
        }
        return new ManagementBoardViews.ManagementBoardKpis(average, days.size(), percent, onTime, sent,
                in.plants().size(), month, streak, streakPlant);
    }

    private record Entry(String plant, String label, int colour, String month, Row fill, Row plan,
            Judgement judgement) {
    }

    private static List<Row> needing(List<Row> fills, String plant, String month) {
        return fills.stream().filter(row -> row.scored() && "ACTION_PLAN".equals(row.result())
                && plant.equals(row.plant()) && month.equals(row.period())).toList();
    }

    private static ManagementBoardCell cell(Inputs in, Map<UUID, Row> planOf, Map<String, Map<String, Double>> scores,
            String plant, String month, boolean inProgress) {
        Double score = scores.get(month).get(plant);
        List<Row> mine = in.fills().stream().filter(row -> plant.equals(row.plant()) && month.equals(row.period()))
                .toList();
        if (score == null) {
            Status status = !inProgress ? Status.NO_SCORE
                    : mine.stream().anyMatch(Row::sent) ? Status.IN_REVIEW : Status.NOT_SENT;
            return new ManagementBoardCell(month, null, status.name(), status.word(),
                    mine.isEmpty() ? null : mine.get(0).id());
        }
        Status worst = Status.NO_PLAN;
        Row about = mine.stream().filter(Row::scored).findFirst().orElse(mine.get(0));
        int urgency = Status.NO_PLAN.urgency() + 1;
        for (Row fill : needing(in.fills(), plant, month)) {
            Judgement judgement = judge(fill, planOf.get(fill.id()), in.dueDays(), in.today());
            if (judgement.status().urgency() < urgency) {
                urgency = judgement.status().urgency();
                worst = judgement.status();
                about = fill;
            }
        }
        // Where the Action plan was sent in by the plant head and has its own score, that score is the one drawn (the
        // status and the word do not change); everywhere else the month's score is.
        Row plan = planOf.get(about.id());
        boolean sentIn = worst == Status.SUBMITTED || worst == Status.LATE;
        Double shown = sentIn && plan != null && plan.score() != null ? plan.score().doubleValue() : score;
        return new ManagementBoardCell(month, shown, worst.name(), worst.word(), about.id());
    }

    private static ManagementBoardPlant tile(String plant, String label, int colour, List<String> months,
            List<Double> points, List<Entry> mine, String inProgress) {
        int last = months.size() - 1;
        Double latest = last < 0 ? null : points.get(last);
        Double change = null;
        String changeMonth = null;
        if (latest != null) {
            for (int back = last - 1; back >= 0; back--) {
                if (points.get(back) != null) {
                    change = BigDecimal.valueOf(latest).subtract(BigDecimal.valueOf(points.get(back))).doubleValue();
                    changeMonth = months.get(back);
                    break;
                }
            }
        }
        // The month in progress shows its score as soon as it has one, but "n of m" counts the months that ended only.
        int ended = months.size() - (inProgress != null && months.contains(inProgress) ? 1 : 0);
        int submitted = (int) points.subList(0, ended).stream().filter(score -> score != null).count();
        return new ManagementBoardPlant(plant, label, colour, last < 0 ? null : months.get(last), latest, change,
                changeMonth, points, submitted, ended, sent(mine), submitted(mine), overdue(mine));
    }

    private static ManagementBoardPlan planView(Entry entry) {
        Judgement judgement = entry.judgement();
        return new ManagementBoardPlan(entry.plant(), entry.label(), entry.colour(), entry.month(),
                entry.fill().score() == null ? null : entry.fill().score().doubleValue(),
                judgement.scoredOn() == null ? null : judgement.scoredOn().toString(),
                judgement.dueOn() == null ? null : judgement.dueOn().toString(),
                judgement.sentOn() == null ? null : judgement.sentOn().toString(), judgement.status().name(),
                judgement.text(), judgement.overdueDays(), entry.fill().id(),
                entry.plan() == null ? null : entry.plan().id());
    }

    /** Plans sent to a plant head: those whose day was stamped. */
    private static int sent(List<Entry> entries) {
        return (int) entries.stream().filter(entry -> entry.judgement().status() != Status.NO_DATE).count();
    }

    private static int submitted(List<Entry> entries) {
        return (int) entries.stream().filter(entry -> entry.judgement().status() == Status.SUBMITTED
                || entry.judgement().status() == Status.LATE).count();
    }

    private static int overdue(List<Entry> entries) {
        return (int) entries.stream().filter(entry -> entry.judgement().status() == Status.OVERDUE).count();
    }
}
