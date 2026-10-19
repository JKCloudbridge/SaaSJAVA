package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One switch of a member's notification preferences, with the words the screen shows (Sprint 25, ADR-0156).
 *
 * @param category {@code APPROVALS}, {@code WORKFLOWS} or {@code ACCOUNT}
 * @param categoryLabel the words for the category
 * @param description what the category covers
 * @param channel {@code IN_APP} or {@code MAIL}
 * @param channelLabel the words for the channel
 * @param enabled whether the member wants it (a switch they never touched is on)
 * @param locked whether the platform fixes it on: the member cannot switch it off
 */
public record PreferenceChoiceView(@NotNull String category, @NotNull String categoryLabel,
        @NotNull String description, @NotNull String channel, @NotNull String channelLabel,
        @NotNull Boolean enabled, @NotNull Boolean locked) {
}
