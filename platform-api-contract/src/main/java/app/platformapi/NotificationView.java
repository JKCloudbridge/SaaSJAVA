package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One in-app notice of the signed-in member (Sprint 25, ADR-0154). The words are built when the notice is read, from
 * the template of its kind, so a change of text reaches old notices. They hold labels of the organization and never a
 * value of a record.
 *
 * @param id the identifier of the notice
 * @param kind what happened, one of the kinds the preferences catalogue lists
 * @param category the category a preference switches (for example {@code APPROVALS})
 * @param title the headline
 * @param body the sentence under it
 * @param href where the notice leads, as a path on the organization's own host, or null when it leads nowhere. The
 *        page it opens decides what the member may see: a record they cannot read is "not found"
 * @param read whether the member has read it
 * @param createdAt when it was created
 */
public record NotificationView(@NotNull UUID id, @NotNull String kind, @NotNull String category,
        @NotNull String title, @NotNull String body, String href, @NotNull Boolean read, @NotNull Instant createdAt) {

    /** Prints the identity only: the words are built for one person. */
    @Override
    public String toString() {
        return "NotificationView[" + id + "]";
    }
}
