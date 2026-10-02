package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A platform administrator sets up an organization for a client and invites its first administrator. The organization
 * stays closed until that person accepts.
 *
 * @param displayName the organization name for people
 * @param slug the short name that becomes the first label of its host
 * @param planKey the plan the organization starts on
 * @param email the address of the first administrator
 */
public record ProvisionOrganizationRequest(@NotBlank @Size(max = 200) String displayName,
        @NotBlank @Size(max = 63) String slug, @NotBlank @Size(max = 40) String planKey,
        @NotBlank @Size(max = 254) String email) {

    @Override
    public String toString() {
        return "ProvisionOrganizationRequest[redacted]";
    }
}
