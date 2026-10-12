package app.platform.metadata.internal;

/**
 * The end of a rehearsal (ADR-0066): the changes were applied by the real rules inside a transaction so that every rule
 * and dependency could be checked, and the transaction must now be rolled back so that nothing stays. Throwing is how
 * the work leaves the transaction without committing; {@link MetadataAdministration#rehearse} catches it and hands the
 * result on. It carries no stack trace, as it is not an error.
 */
final class Rehearsed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Object result;

    Rehearsed(Object result) {
        super("rehearsal", null, false, false);
        this.result = result;
    }

    Object result() {
        return result;
    }
}
