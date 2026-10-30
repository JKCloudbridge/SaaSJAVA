package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * What a definition does with a sample (Sprint 28, ADR-0217).
 *
 * @param direction {@code OUTBOUND} or {@code INBOUND}
 * @param result the values that would be sent (by external field name) or stored (by field API name), as text
 * @param skipped the fields left out, each with a fixed sentence and no value
 * @param problems what is wrong with the definition or the sample, by place; empty when it works
 */
public record MappingDryRunView(@NotNull String direction, @NotNull Map<String, String> result,
        @NotNull List<MappingSkippedField> skipped, @NotNull Map<String, List<String>> problems) {

    public MappingDryRunView {
        result = Map.copyOf(result);
        skipped = List.copyOf(skipped);
        problems = Map.copyOf(problems);
    }

    @Override
    public String toString() {
        return "MappingDryRunView[redacted]";
    }
}
