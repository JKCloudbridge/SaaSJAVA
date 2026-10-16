package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One tab of a resolved record page (Sprint 20): its label and the blocks it shows, already filtered to what the
 * caller may reach. A tab left with no block (every block it had turned out to be unreachable) is dropped from the
 * page entirely, exactly as an application drops a heading that leads nowhere (ADR-0128).
 */
public record RecordPageTabView(@NotNull String label, @NotNull List<RecordPageBlockView> blocks) {

    public RecordPageTabView {
        blocks = List.copyOf(blocks);
    }
}
