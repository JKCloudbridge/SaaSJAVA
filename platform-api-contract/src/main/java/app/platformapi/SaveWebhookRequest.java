package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Makes or changes a webhook subscription (Sprint 32, ADR-0274). A new one carries version 0. The address, the events
 * and the objects are checked on the server; a change is accepted only while the subscription is switched off and
 * makes the last verification void.
 *
 * @param name 2 to 40 lower case letters, digits and dashes, starting with a letter
 * @param address the https address to send to
 * @param events the events to ask for, from the catalogue
 * @param objects the API names of the only objects to tell about, or null for every object the member may read
 * @param version the version read, 0 for a new subscription
 */
public record SaveWebhookRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 2048) String address,
        @NotEmpty @Size(max = 20) List<@NotNull @Size(max = 60) String> events,
        @Size(max = 50) List<@NotNull @Size(max = 80) String> objects, long version) {

    public SaveWebhookRequest {
        events = events == null ? List.of() : List.copyOf(events);
        objects = objects == null ? null : List.copyOf(objects);
    }

    /** Prints nothing typed: the address belongs to the organization. */
    @Override
    public String toString() {
        return "SaveWebhookRequest[redacted]";
    }
}
