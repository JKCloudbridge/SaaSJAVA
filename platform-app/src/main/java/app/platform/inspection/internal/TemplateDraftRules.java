package app.platform.inspection.internal;

import app.platform.sharedkernel.solution.SheetDraftRules;
import app.platform.sheets.SheetLifecycle;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Once a template has been published, a new revision of it is made only from inside a change request, by a person asked
 * to decide that request (FA8, ADR-0202). So nobody starts a draft of its sheet by hand: not as a blank draft, not as a
 * copy of a revision, not from an uploaded file. The first draft of a template that was never published is not touched
 * by this, and the draft a change request makes for itself does not ask: it goes through the sheet lifecycle contract.
 * The property {@code platform.inspection.hold-published-templates} (default true) is switched off only by the tests of
 * the generic sheet module, which need a second draft of a sheet without a change request.
 */
@Component
@ConditionalOnProperty(name = "platform.inspection.hold-published-templates", havingValue = "true",
        matchIfMissing = true)
class TemplateDraftRules implements SheetDraftRules {

    static final String WORDS = "A new revision of a published template is made from a change request. Ask for a "
            + "change on the Change requests tab of the template.";

    private final SheetLifecycle sheets;

    TemplateDraftRules(SheetLifecycle sheets) {
        this.sheets = sheets;
    }

    @Override
    public Optional<String> refuseNewDraft(String ownerObject, UUID ownerId) {
        if (!Templates.OBJECT.equals(ownerObject)) {
            return Optional.empty();
        }
        return sheets.everPublished(ownerObject, ownerId) ? Optional.of(WORDS) : Optional.empty();
    }
}
