package app.platform.inspection.internal;

import app.platform.sheets.SheetLifecycle;
import java.util.Optional;

/**
 * Whether a template can be sent for approval now, decided in one place (FA10, ADR-0204). The lifecycle view asks it
 * to tell the screen whether to offer Submit, and the approval hook asks it again to refuse a send that cannot work,
 * so the button and the refusal never differ. The hook stays the final judge.
 *
 * <p>The rule is the template's own, not the approval engine's: a template is sent once, as a first Revision. It is not
 * retired, it has a draft, it has never been published, and no review of it is pending. After the first publish a new
 * Revision comes only from a change request.
 *
 * <p>The words are fixed; they repeat nothing a person typed.
 */
final class TemplateSendRule {

    /** Why a template cannot be sent: a stable key (for the audit of a refusal) and the sentence a person reads. */
    record Block(String reason, String words) {
    }

    static final Block RETIRED = new Block("template_retired",
            "This template is retired and cannot be sent for approval.");
    static final Block BEING_APPROVED = new Block("already_being_approved", "It is already being approved.");
    static final Block PUBLISHED = new Block("template_published",
            "A published template changes only through a change request.");
    static final Block NO_DRAFT = new Block("no_first_revision",
            "Nothing to send yet: the template has no first Revision in draft.");

    private TemplateSendRule() {
    }

    /**
     * What stops the template from being sent.
     *
     * @param summary what its versions add up to
     * @param retired whether it is retired
     * @return the reason, or empty when it can be sent
     */
    static Optional<Block> blocked(SheetLifecycle.Summary summary, boolean retired) {
        if (retired) {
            return Optional.of(RETIRED);
        }
        if (summary.active() > 0 || summary.retired() > 0) {
            return Optional.of(PUBLISHED);
        }
        if (summary.inReview() > 0) {
            return Optional.of(BEING_APPROVED);
        }
        if (summary.draft() == 0) {
            return Optional.of(NO_DRAFT);
        }
        return Optional.empty();
    }
}
