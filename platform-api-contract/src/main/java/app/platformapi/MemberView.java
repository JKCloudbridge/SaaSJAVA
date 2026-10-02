package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * A member of the organization as its administrators see them.
 *
 * @param id the membership (not the person's account)
 * @param email the member's address
 * @param displayName the member's name
 * @param status {@code ACTIVE} or {@code DEACTIVATED}
 * @param administrator whether the member may administer the organization (a stop-gap until access policies)
 * @param foundingAdministrator the historical fact that this person created the organization; grants nothing
 * @param since when the person became a member
 * @param you whether this member is the caller (for presentation only)
 * @param licence the key of the licence type the member holds, absent when the member has none (Sprint 6)
 */
public record MemberView(@NotNull UUID id, @NotNull String email, @NotNull String displayName, @NotNull String status,
        boolean administrator, boolean foundingAdministrator, @NotNull Instant since, boolean you, String licence) {

    @Override
    public String toString() {
        return "MemberView[redacted]";
    }
}
