package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Changes some of the signed-in member's switches (Sprint 25, ADR-0156). Only the switches named change. A switch the
 * platform fixes on is refused with a message, never silently kept.
 *
 * @param choices the switches to set
 */
public record UpdateNotificationPreferencesRequest(
        @NotNull @Size(min = 1, max = 20) List<@Valid PreferenceChoiceRequest> choices) {

    /** Copies the list. */
    public UpdateNotificationPreferencesRequest {
        choices = choices == null ? null : List.copyOf(choices);
    }
}
