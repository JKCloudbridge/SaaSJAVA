package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * The question behind every endpoint of this module: "does the caller hold the ability this action needs?", and what to
 * do when the answer is yes but the target is something the platform protects (ADR-0059). The ability is checked inside
 * the transaction of the work by {@link OrganizationAdministration}; a refusal for a protected definition is recorded
 * after that transaction ended, with its own reason, and the caller is told in words that the platform defines it.
 */
@Component
class MetadataAdministration {

    static final String PROTECTED_MESSAGE =
            "This is defined by the platform and cannot be changed. You can add your own fields to a standard "
                    + "object where the platform allows it.";

    private final OrganizationAdministration administration;
    private final MetadataAudit audit;

    MetadataAdministration(OrganizationAdministration administration, MetadataAudit audit) {
        this.administration = administration;
        this.audit = audit;
    }

    /**
     * Runs the work in one transaction after checking the ability inside it.
     *
     * @throws ApiException {@code NOT_FOUND} on the platform host, {@code FORBIDDEN} without the ability or for a
     *         protected definition
     */
    <T> T run(String action, Ability ability, Function<OrganizationAdministration.Caller, T> work) {
        try {
            return administration.asAdministrator(action, ability, work);
        } catch (ProtectedDefinition refused) {
            audit.protectedChangeRefused(refused.actor(), refused.action(), refused.object());
            throw new ApiException(ErrorCode.FORBIDDEN, PROTECTED_MESSAGE);
        }
    }
}
