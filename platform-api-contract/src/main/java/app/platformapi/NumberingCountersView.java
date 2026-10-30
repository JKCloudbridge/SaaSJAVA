package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The combinations of one series that already have a counter, for the administrator's read-only table (FA11B,
 * ADR-0206). Counters only: no record, no template.
 *
 * @param counters one page, in the order of the combinations
 * @param nextCursor where the next page starts, or null at the end
 */
public record NumberingCountersView(@NotNull List<Counter> counters, String nextCursor) {

    public NumberingCountersView {
        counters = List.copyOf(counters);
    }

    /**
     * One combination.
     *
     * @param combination the identification and the parts as they were typed, joined with slashes
     * @param nextValue the count the next number of the combination gets
     * @param lastIssued the count of the last number given (0 when the counter was only raised)
     */
    public record Counter(@NotNull String combination, @NotNull Long nextValue, @NotNull Long lastIssued) {

        @Override
        public String toString() {
            return "Counter[redacted]";
        }
    }

    @Override
    public String toString() {
        return "NumberingCountersView[redacted]";
    }
}
