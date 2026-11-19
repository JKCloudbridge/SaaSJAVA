package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.sharedkernel.solution.ApplicationSettings;
import app.platform.sharedkernel.solution.FillCorrections;
import app.platform.sheets.FillLifecycle;
import app.platformapi.QueryFilterNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a fill-in's score means (MC2B, ADR-0261): the one place that turns the number the sheet calculated into the
 * result written on the header, against the limit of the application the fill-in belongs to. The sheets module
 * calculates ({@link FillLifecycle#scoreOf}); the application settings hold the limit; this class only compares them
 * and keeps the header in step.
 *
 * <p><strong>The rule.</strong> A score strictly below the limit needs an Action plan ({@code ACTION_PLAN}); a score
 * equal to the limit or above is a {@code PASS}. A fill-in whose sheet marks no score has neither field. A sheet that
 * marks a score that cannot be calculated has no answer, and the review cannot go on ({@link FillApprovalHooks}). An
 * application that declares no limit gets the score and no result: the platform holds no limit of its own.
 *
 * <p><strong>Written when.</strong> At every approval of a review, by the approval hook through its narrow writer, and
 * after a correction of the cells of a fill-in that is not under review, here, through the platform's header writer
 * ({@link FillCorrections}). A correction while a review is open needs nothing: the next approval calculates again.
 *
 * <p>A score, a result and a limit are the organization's: nothing here is written to a log, an audit record or a
 * metric, and no exception message names one.
 */
@Component
class FillScores implements FillCorrections {

    /** The name of the limit setting; the application's solution file declares it. */
    static final String LIMIT = "scoreLimit";

    /** What the cells say about the score of a fill-in. */
    enum State {
        /** The sheet marks no score: the fill-in has none and is judged as before. */
        NO_SCORE,
        /** The sheet marks a score that cannot be calculated. */
        UNREADABLE,
        /** The score was calculated. */
        JUDGED
    }

    /**
     * The answer.
     *
     * @param state what was found
     * @param score the score in percent points with two decimals, or null unless judged
     * @param result {@code PASS} or {@code ACTION_PLAN}, or null when nothing was judged or the application declares no
     *        limit
     */
    record Judgement(State state, BigDecimal score, String result) {

        /** Prints the state only. */
        @Override
        public String toString() {
            return "Judgement[" + state + "]";
        }
    }

    private final RecordSets sets;
    private final BackgroundRecords records;
    private final FillLifecycle fills;
    private final ApplicationSettings settings;
    private final FillHeaders headers;

    FillScores(RecordSets sets, BackgroundRecords records, FillLifecycle fills, ApplicationSettings settings,
            FillHeaders headers) {
        this.sets = sets;
        this.records = records;
        this.fills = fills;
        this.settings = settings;
        this.headers = headers;
    }

    /** Calculates and compares, writing nothing. */
    Judgement judge(String fillObject, DataRecord header) {
        FillLifecycle.Score found = fills.scoreOf(fillObject, header.id());
        return switch (found.state()) {
            case NOT_MARKED -> new Judgement(State.NO_SCORE, null, null);
            case NOT_CALCULATED -> new Judgement(State.UNREADABLE, null, null);
            case CALCULATED -> new Judgement(State.JUDGED, found.percent(), resultFor(header, found.percent()));
        };
    }

    private String resultFor(DataRecord header, BigDecimal score) {
        Object application = header.fields().get(RecordSets.APPLICATION_FIELD);
        if (!(application instanceof String name) || name.isBlank()) {
            return null;
        }
        OptionalInt limit = settings.value(name, LIMIT);
        if (limit.isEmpty()) {
            return null;
        }
        return score.compareTo(BigDecimal.valueOf(limit.getAsInt())) < 0 ? Fills.ACTION_PLAN : Fills.PASS;
    }

    /** The fields to write for a judgement, only those that differ from the header as it is; empty when none does. */
    Map<String, String> changes(DataRecord header, Judgement judgement) {
        Map<String, String> changes = new HashMap<>();
        String score = judgement.score().toPlainString();
        Object stored = header.fields().get(Fills.SCORE);
        if (!(stored != null && sameNumber(stored, judgement.score()))) {
            changes.put(Fills.SCORE, score);
        }
        if (judgement.result() != null && !Objects.equals(header.fields().get(Fills.RESULT), judgement.result())) {
            changes.put(Fills.RESULT, judgement.result());
        }
        return changes;
    }

    private static boolean sameNumber(Object stored, BigDecimal score) {
        try {
            return new BigDecimal(String.valueOf(stored)).compareTo(score) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The Action plan that answers a fill-in, or null when none was started. At most one can exist (unique link). */
    UUID actionPlanOf(String fillObject, UUID fillId) {
        List<DataRecord> found = records.scan(fillObject,
                new QueryFilterNode("EQ", null, Fills.PARENT_FILL, List.of(fillId.toString())), null, 2);
        return found.isEmpty() ? null : found.get(0).id();
    }

    // ---- the correction of a fill-in that is not under review ----

    @Override
    public boolean serves(String fillObject) {
        return sets.fills().contains(fillObject);
    }

    @Override
    public void corrected(String fillObject, UUID fillId) {
        DataRecord header = records.find(fillObject, fillId).orElse(null);
        if (header == null) {
            return;
        }
        Judgement judgement = judge(fillObject, header);
        if (judgement.state() == State.NO_SCORE) {
            return;
        }
        String score = judgement.state() == State.JUDGED ? judgement.score().toPlainString() : null;
        String result = judgement.state() == State.JUDGED ? judgement.result() : null;
        if (Objects.equals(textOf(header.fields().get(Fills.RESULT)), result)
                && (score == null ? header.fields().get(Fills.SCORE) == null
                        : header.fields().get(Fills.SCORE) != null
                                && sameNumber(header.fields().get(Fills.SCORE), judgement.score()))) {
            return;
        }
        try {
            headers.scores(fillObject, fillId, score, result);
        } catch (BackgroundWriteException refused) {
            // Not a refusal for the person: the header is the platform's own record. The correction is undone and the
            // error tracking sees a failure without a value.
            throw new IllegalStateException("The score of a fill-in could not follow its correction ("
                    + refused.reason().key() + ")");
        }
    }

    private static String textOf(Object stored) {
        return stored == null ? null : String.valueOf(stored);
    }
}
