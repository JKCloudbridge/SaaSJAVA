package app.platform.integration.internal;

/**
 * How the rows of a file are applied in bounded batches, so that a restart at any point repeats no row and skips none
 * (Sprint 29A, ADR-0241). Pure arithmetic over three numbers: how many rows the file has, how many are done (recorded
 * in the same transaction as the rows themselves) and how many go in one batch.
 *
 * <p>A batch is the half-open range {@code [from, to)}. The next batch after {@code done} rows always starts at
 * {@code done}, so whatever was recorded is exactly what was written, and the batches of any starting point together
 * cover {@code [done, total)} once.
 */
final class FileBatchPlan {

    /** A range of rows, counted from zero, {@code to} not included. */
    record Batch(int from, int to) {

        int size() {
            return to - from;
        }
    }

    private FileBatchPlan() {
    }

    /**
     * The batch that follows {@code done} rows, or null when the file is complete.
     *
     * @param total the number of rows of the file
     * @param done the rows already applied and recorded
     * @param batchSize the most rows of one batch, at least 1
     */
    static Batch next(int total, int done, int batchSize) {
        if (total < 0 || done < 0 || done > total || batchSize < 1) {
            throw new IllegalArgumentException("Impossible file progress");
        }
        return done == total ? null : new Batch(done, (int) Math.min((long) done + batchSize, total));
    }

    /** How many batches remain after {@code done} rows. */
    static int remaining(int total, int done, int batchSize) {
        int left = total - done;
        return left <= 0 ? 0 : (left + batchSize - 1) / batchSize;
    }
}
