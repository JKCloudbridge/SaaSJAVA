package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.SheetLifecycle.Summary;
import org.junit.jupiter.api.Test;

/** The rule of whether a template can be sent for approval (FA10, ADR-0204), for every state, without a database. */
class TemplateSendRuleTest {

    private static Summary versions(int active, int approved, int inReview, int draft, int retired) {
        int total = Math.max(1, active + approved + inReview + draft);
        return new Summary(active, approved, inReview, draft, retired, total, 1);
    }

    @Test
    void aFirstRevisionInDraftCanBeSent() {
        assertThat(TemplateSendRule.blocked(versions(0, 0, 0, 1, 0), false)).isEmpty();
    }

    @Test
    void aTemplateWithNoVersionAtAllHasNothingToSend() {
        assertThat(TemplateSendRule.blocked(new Summary(0, 0, 0, 0, 0, 0, 0), false))
                .contains(TemplateSendRule.NO_DRAFT);
    }

    @Test
    void aReturnedTemplateWithNoNewDraftHasNothingToSend() {
        assertThat(TemplateSendRule.blocked(new Summary(0, 0, 0, 0, 0, 1, 1), false))
                .contains(TemplateSendRule.NO_DRAFT);
    }

    @Test
    void aTemplateBeingApprovedCannotBeSentAgain() {
        assertThat(TemplateSendRule.blocked(versions(0, 0, 1, 0, 0), false)).contains(TemplateSendRule.BEING_APPROVED);
    }

    @Test
    void aPublishedTemplateChangesOnlyThroughAChangeRequestEvenWithAHeldCopyInDraft() {
        assertThat(TemplateSendRule.blocked(versions(1, 0, 0, 0, 0), false)).contains(TemplateSendRule.PUBLISHED);
        assertThat(TemplateSendRule.blocked(versions(1, 0, 0, 1, 0), false)).contains(TemplateSendRule.PUBLISHED);
        assertThat(TemplateSendRule.blocked(versions(1, 0, 1, 0, 0), false)).contains(TemplateSendRule.PUBLISHED);
    }

    @Test
    void aTemplateRetiredBeforeOrAfterItsRevisionsWasRetiredCannotBeSent() {
        assertThat(TemplateSendRule.blocked(versions(0, 0, 0, 1, 0), true)).contains(TemplateSendRule.RETIRED);
        assertThat(TemplateSendRule.blocked(versions(0, 0, 0, 0, 1), true)).contains(TemplateSendRule.RETIRED);
    }

    @Test
    void theWordsAreFixedAndRepeatNothingATemplateHolds() {
        for (TemplateSendRule.Block block : new TemplateSendRule.Block[] {TemplateSendRule.RETIRED,
            TemplateSendRule.BEING_APPROVED, TemplateSendRule.PUBLISHED, TemplateSendRule.NO_DRAFT}) {
            assertThat(block.words()).isNotBlank().doesNotContain("{", "%");
            assertThat(block.reason()).matches("[a-z_]+");
        }
    }
}
