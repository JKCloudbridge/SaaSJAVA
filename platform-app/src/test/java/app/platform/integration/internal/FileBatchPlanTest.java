package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.internal.FileBatchPlan.Batch;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * How the rows of a file are cut into batches (Sprint 29A, ADR-0241): the batches of any starting point cover the rows
 * that are left once and only once, in order, none larger than the bound; and a restart after any number of recorded
 * batches continues exactly where the uninterrupted run would have been.
 */
class FileBatchPlanTest {

    private static List<Batch> from(int total, int done, int size) {
        List<Batch> batches = new ArrayList<>();
        for (Batch batch = FileBatchPlan.next(total, done, size); batch != null;
                batch = FileBatchPlan.next(total, batch.to(), size)) {
            batches.add(batch);
        }
        return batches;
    }

    @Test
    void aFileOfFiveRowsInBatchesOfTwoIsThreeBatchesAndTheLastIsShort() {
        assertThat(from(5, 0, 2)).containsExactly(new Batch(0, 2), new Batch(2, 4), new Batch(4, 5));
        assertThat(from(5, 2, 2)).as("a restart after one batch").containsExactly(new Batch(2, 4), new Batch(4, 5));
        assertThat(from(5, 5, 2)).as("nothing is left").isEmpty();
        assertThat(from(0, 0, 2)).isEmpty();
        assertThat(FileBatchPlan.remaining(5, 0, 2)).isEqualTo(3);
        assertThat(FileBatchPlan.remaining(5, 4, 2)).isEqualTo(1);
        assertThat(FileBatchPlan.remaining(5, 5, 2)).isZero();
    }

    @Test
    void impossibleProgressIsRefusedNotGuessed() {
        assertThatThrownBy(() -> FileBatchPlan.next(5, 6, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileBatchPlan.next(5, -1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileBatchPlan.next(5, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileBatchPlan.next(-1, 0, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void theBatchesOfAnyStartCoverTheRowsThatAreLeastOnceAndOnlyOnce(
            @ForAll @IntRange(min = 0, max = 5000) int total, @ForAll @IntRange(min = 1, max = 500) int size,
            @ForAll @IntRange(min = 0, max = 100) int percent) {
        int done = total * percent / 100;

        List<Batch> batches = from(total, done, size);

        int next = done;
        for (Batch batch : batches) {
            assertThat(batch.from()).as("no row skipped and none repeated").isEqualTo(next);
            assertThat(batch.size()).isBetween(1, size);
            next = batch.to();
        }
        assertThat(next).isEqualTo(total);
        assertThat(batches.size()).isEqualTo(FileBatchPlan.remaining(total, done, size));
    }

    @Property
    void aRestartAfterAnyNumberOfRecordedBatchesContinuesWhereTheRunWouldHaveBeen(
            @ForAll @IntRange(min = 1, max = 3000) int total, @ForAll @IntRange(min = 1, max = 300) int size,
            @ForAll @IntRange(min = 0, max = 20) int batchesBeforeTheCut) {
        List<Batch> uninterrupted = from(total, 0, size);
        int written = 0;
        List<Batch> firstRun = new ArrayList<>();
        for (Batch batch = FileBatchPlan.next(total, 0, size); batch != null && firstRun.size() < batchesBeforeTheCut;
                batch = FileBatchPlan.next(total, batch.to(), size)) {
            firstRun.add(batch);
            written = batch.to();
        }

        List<Batch> secondRun = from(total, written, size);

        List<Batch> together = new ArrayList<>(firstRun);
        together.addAll(secondRun);
        assertThat(together).as("what was written before the cut plus what is written after it")
                .isEqualTo(uninterrupted);
    }
}
