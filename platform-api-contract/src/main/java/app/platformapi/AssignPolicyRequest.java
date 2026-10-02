package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Gives a member an access policy (Sprint 7). A policy that needs a licence uses one from the organization's pool.
 *
 * @param policyId the access policy of the organization
 */
public record AssignPolicyRequest(@NotNull UUID policyId) {
}
