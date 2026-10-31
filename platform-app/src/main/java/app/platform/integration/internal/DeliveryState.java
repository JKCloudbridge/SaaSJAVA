package app.platform.integration.internal;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life of an outbound delivery as one pure table (Sprint 29, ADR-0230), the same table the database guard holds:
 * the application consults it before a move and the guard refuses any move it lacks, so neither can drift without a
 * test noticing.
 *
 * <p>A delivery waits as a row ({@link #PENDING}, with a due time), is held by a worker with a lease
 * ({@link #SENDING}), may wait for a job on the other side ({@link #WAITING_REMOTE}) and ends in {@link #DONE},
 * {@link #SKIPPED} (nothing to do, with the reason), {@link #SUPERSEDED} (a later change replaced it) or
 * {@link #DEAD_LETTERED} (it will not succeed by itself; an administrator may replay it). {@link #FAILED} is kept for a
 * receive that was refused and for the rows of Sprint 28.
 */
enum DeliveryState {
    PENDING,
    SENDING,
    WAITING_REMOTE,
    DONE,
    FAILED,
    SKIPPED,
    SUPERSEDED,
    DEAD_LETTERED;

    private static final Set<DeliveryState> OPEN = EnumSet.of(PENDING, SENDING, WAITING_REMOTE);

    /** Whether the delivery still has work to do. */
    boolean open() {
        return OPEN.contains(this);
    }

    /** Whether the delivery has ended for good (a dead letter can still be replayed). */
    boolean terminal() {
        return !open();
    }

    /** Whether a delivery may move from this state to the other, other than by a replay. */
    boolean mayMoveTo(DeliveryState next) {
        return switch (this) {
            // Waiting again (a deferral or a retry) is a move to itself or back to pending.
            case PENDING, SENDING -> true;
            // A job on the other side is asked about, never sent again.
            case WAITING_REMOTE -> next == WAITING_REMOTE || next == DONE || next == DEAD_LETTERED
                    || next == SKIPPED || next == FAILED;
            case DONE, FAILED, SKIPPED, SUPERSEDED, DEAD_LETTERED -> false;
        };
    }

    /** The only move out of a dead letter: a replay makes it pending again as a new generation. */
    static boolean mayReplay(DeliveryState from) {
        return from == DEAD_LETTERED;
    }

    /** The open states, as the statements of the queue write them. */
    static String openList() {
        return "'PENDING', 'SENDING', 'WAITING_REMOTE'";
    }
}
