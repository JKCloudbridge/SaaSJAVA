package app.platform.sheets;

import java.util.Objects;

/**
 * A move of a version that the lifecycle does not allow right now (FA4, ADR-0188). The words are fixed and say what to
 * do; they never repeat anything a person typed, except {@link Reason#INVALID}, which carries the words and the place
 * of the grid validator (names of the schema and positions, never a typed text). The reason key is stable: it is what
 * the audit trail and the metrics keep.
 */
public final class SheetMoveRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a move was refused. */
    public enum Reason {
        /** There is no draft to send for approval. */
        NO_DRAFT("There is no draft to send for approval. Start a new version first."),
        /** The draft has nothing in it yet. */
        EMPTY("The draft is still empty. Add content to it before sending it for approval."),
        /** The grid does not pass the checks of a sheet; the words and the place follow. */
        INVALID("The draft is not valid."),
        /** Another version of the sheet is already being reviewed. */
        ALREADY_IN_REVIEW("Another version of this sheet is already being reviewed."),
        /** The version asked for is not an approved version. */
        NOT_APPROVED("Only an approved version can be published."),
        /** No version of the sheet is in use. */
        NOTHING_ACTIVE("No version of this sheet is in use.");

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
    public SheetMoveRefusal(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").words());
        this.reason = reason;
    }

    /** A refusal with the fixed words followed by the validator's own (names of the schema, never typed text). */
    public SheetMoveRefusal(Reason reason, String detail) {
        super(Objects.requireNonNull(reason, "reason").words() + " " + detail);
        this.reason = reason;
    }

    /** The stable reason. */
    public Reason reason() {
        return reason;
    }
}
