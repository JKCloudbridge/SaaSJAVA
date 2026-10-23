package app.platform.sheets;

import java.util.Objects;

/**
 * A move of a fill-in that the lifecycle does not allow right now (FF1, ADR-0192). The words are fixed and say what to
 * do; they never repeat anything a person typed, except {@link Reason#INCOMPLETE}, which carries the place of the first
 * value that is missing (names of the sheet's columns and rows by identifier, never a typed text). The reason key is
 * stable: it is what the audit trail and the metrics keep.
 */
public final class FillRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a move was refused. */
    public enum Reason {
        /** The version asked for is not the one in use, or does not exist. */
        NOT_IN_USE("A fill-in can be started only on the version that is in use."),
        /** The header already has a fill-in row. */
        ALREADY_STARTED("This fill-in was already started."),
        /** There is no such fill-in. */
        NO_FILL("This fill-in does not exist."),
        /** The fill-in is not a draft or a returned fill-in, so it cannot be sent. */
        NOT_FILLABLE("Only a fill-in that is a draft, or was returned, can be sent for review."),
        /** The person asking is not the person who started the fill-in. */
        NOT_THE_FILLER("Only the person who started a fill-in can send it."),
        /** The fill-in has no rows. */
        NO_ROWS("This fill-in has no rows yet. Add at least one row before sending it."),
        /** A required value is missing; the place follows. */
        INCOMPLETE("A value that is required is missing."),
        /** The cells no longer fit the sheet version they were made for. */
        INVALID("The fill-in is not valid."),
        /** The sheet version the fill-in is pinned to has been retired. */
        VERSION_RETIRED("The sheet this fill-in was made for is retired, so the fill-in can no longer be sent.");

        private final String words;

        Reason(String words) {
            this.words = words;
        }

        /** The fixed words for people. */
        public String words() {
            return words;
        }
    }

    private final Reason reason;

    /** A refusal with the fixed words of the reason. */
    public FillRefusal(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").words());
        this.reason = reason;
    }

    /** A refusal with the fixed words followed by the place of the problem (identifiers, never a typed text). */
    public FillRefusal(Reason reason, String place) {
        super(Objects.requireNonNull(reason, "reason").words() + " (" + place + ")");
        this.reason = reason;
    }

    /** The stable reason. */
    public Reason reason() {
        return reason;
    }
}
