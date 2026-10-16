package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A record a link field could point to, as a picker offers it (Sprint 19, ADR-0129). The label is typed by a person, so
 * it never prints.
 *
 * @param id the identifier to store in the link
 * @param label the name for people
 */
public record LookupOptionView(@NotNull String id, @NotNull String label) {

    @Override
    public String toString() {
        return "LookupOptionView[redacted]";
    }
}
