package app.platform.sharedkernel.solution;

import java.util.UUID;

/**
 * What the module that owns the header of a fill-in does when the cells of a fill-in that is no longer under review
 * were corrected by a person who may override the lock (MC2B, ADR-0261). The sheets module owns the cells and calls it,
 * in the transaction of the correction, and the owner of the header implements it: the same inversion as the approval
 * hooks and the file rules, so {@code sheets} never depends on the module that implements it. It exists so that what
 * the header says about the cells (the score and the result) cannot be left behind by a correction.
 *
 * <p>It is called only when the header is not locked by a pending request: while a review is open, the next approval
 * calculates again, so nothing is lost. It carries no cell and no value: the implementation reads what it needs
 * itself, and must write nothing to a log.
 */
public interface FillCorrections {

    /** Whether this implementation serves the fill-in object. */
    boolean serves(String fillObject);

    /**
     * The cells of the fill-in were corrected in this transaction.
     *
     * @param fillObject the object of the header record
     * @param fillId the header record
     */
    void corrected(String fillObject, UUID fillId);
}
