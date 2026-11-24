package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The use of one connected application against its own limit (Sprint 32, ADR-0280).
 *
 * @param name the name of the connected application
 * @param limits its limits and use, per window
 */
public record ApplicationUsageView(@NotNull String name, @NotNull List<LimitUsageView> limits) {

    public ApplicationUsageView {
        limits = List.copyOf(limits);
    }
}
