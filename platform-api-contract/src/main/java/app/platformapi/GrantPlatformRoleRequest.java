package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Gives a platform role to the person with an existing active account.
 *
 * @param email the address of the account
 * @param role {@code PLATFORM_ADMIN}, {@code PLATFORM_SUPPORT} or {@code PLATFORM_BILLING}
 */
public record GrantPlatformRoleRequest(@NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 40) String role) {

    @Override
    public String toString() {
        return "GrantPlatformRoleRequest[redacted]";
    }
}
