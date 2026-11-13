package app.platform.sheets.internal.formula;

/**
 * A formula was refused. It carries a stable reason (see {@link FormulaWords}) and nothing else: no text of the
 * formula, no position of a character, so that nothing the organization typed can reach a log or an answer
 * (SH2, ADR-0252, ADR-0012). The stack is not filled in; the reason is the whole message.
 */
public final class FormulaRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * Creates the refusal.
     *
     * @param reason the stable reason, one of the keys of {@link FormulaWords}
     */
    public FormulaRefused(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    /** The stable reason. */
    public String reason() {
        return reason;
    }
}
