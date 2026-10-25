package app.platform.sharedkernel.solution;

import java.util.Optional;
import java.util.UUID;

/**
 * A say, for the module that owns a sheet's owner object, in whether a person may start a new draft of the sheet by
 * hand (Functional track FA8, ADR-0202). The module that keeps sheets asks every rule it finds before it makes a draft
 * for a member (a blank first draft, a copy of a version, a draft from an uploaded file); a rule can only refuse. The
 * sheets module knows no business word: which owner objects have such a rule, and why, is the implementer's business.
 *
 * <p>It does not apply to a draft that the owner's own module starts for an approval request: that goes through the
 * lifecycle contract of the sheets module, which asks nobody.
 */
public interface SheetDraftRules {

    /**
     * Whether a new draft of this sheet may not be started by a member now.
     *
     * @param ownerObject the owner's object
     * @param ownerId the owner record
     * @return the fixed words to show when it may not, empty when this rule has nothing against it
     */
    Optional<String> refuseNewDraft(String ownerObject, UUID ownerId);
}
