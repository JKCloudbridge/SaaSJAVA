package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A person named in an approval (Sprint 24): the identifier and the name members see. A person who is no longer an
 * active
 * member has no name ({@code name} is null) and the screens say so; the name is the display name of the member, never
 * an
 * address.
 *
 * @param userId the person
 * @param name the display name of an active member, or null
 */
public record ApprovalPersonView(@NotNull UUID userId, String name) {
}
