package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What the screens need to offer when registering a connected application, from the server so that the browser holds
 * no list of its own (Sprint 31, ADR-0270): the scopes, the limits, the address to ask for a token, and the words that
 * explain whom an application acts as.
 *
 * @param scopes the scopes, with what each allows
 * @param tokenPath the address, relative to the host of the organization, where software asks for a token
 * @param defaultTokenMinutes the life of a token when none is chosen
 * @param minTokenMinutes the shortest life that may be chosen
 * @param maxTokenMinutes the longest life that may be chosen
 * @param maxOverlapHours the longest overlap of an old secret after a rotation
 * @param maxObjects how many objects an application may be limited to
 * @param actingNote what the platform tells whoever registers an application about whom it acts as
 * @param stoppedNote what to do when an application shows as stopped because its member is no longer active
 * @param tokenRequestExample a worked request for a token, with placeholders and no host
 */
public record ConnectedAppCatalogue(@NotNull List<ClientScopeView> scopes, @NotNull String tokenPath,
        int defaultTokenMinutes, int minTokenMinutes, int maxTokenMinutes, int maxOverlapHours, int maxObjects,
        @NotNull String actingNote, @NotNull String stoppedNote, @NotNull String tokenRequestExample) {

    /**
     * One scope.
     *
     * @param key the key software asks for
     * @param title a name for people
     * @param description what it allows, in plain words
     */
    public record ClientScopeView(@NotNull String key, @NotNull String title, @NotNull String description) {
    }
}
