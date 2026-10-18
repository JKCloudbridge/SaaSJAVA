package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.notice.NoticeKind;
import org.junit.jupiter.api.Test;

class PreferenceRulesTest {

    @Test
    void aSwitchNobodyTouchedIsOn() {
        for (NoticeKind.Category category : NoticeKind.Category.values()) {
            assertThat(PreferenceRules.wanted(category, null)).as(category.name()).isTrue();
        }
        for (NoticeKind kind : NoticeKind.values()) {
            assertThat(PreferenceRules.wanted(kind, null)).as(kind.name()).isTrue();
        }
    }

    @Test
    void aPersonCanSwitchOffWhatThePlatformDoesNotFix() {
        assertThat(PreferenceRules.wanted(NoticeKind.Category.APPROVALS, false)).isFalse();
        assertThat(PreferenceRules.wanted(NoticeKind.Category.WORKFLOWS, false)).isFalse();
        assertThat(PreferenceRules.wanted(NoticeKind.APPROVAL_TASK, false)).isFalse();
        assertThat(PreferenceRules.wanted(NoticeKind.WORKFLOW_NOTICE, false)).isFalse();
        assertThat(PreferenceRules.wanted(NoticeKind.APPROVAL_TASK, true)).isTrue();
    }

    @Test
    void theFloorIgnoresWhateverIsStored() {
        assertThat(PreferenceRules.locked(NoticeKind.Category.ACCOUNT)).isTrue();
        assertThat(PreferenceRules.wanted(NoticeKind.Category.ACCOUNT, false)).isTrue();
        assertThat(PreferenceRules.wanted(NoticeKind.TRIAL_NOTICE, false)).isTrue();
        assertThat(PreferenceRules.locked(NoticeKind.TRIAL_NOTICE)).isTrue();
    }

    @Test
    void onlyTheAccountCategoryAndTheKindsOfTheFloorAreLocked() {
        for (NoticeKind.Category category : NoticeKind.Category.values()) {
            assertThat(PreferenceRules.locked(category)).as(category.name())
                    .isEqualTo(category == NoticeKind.Category.ACCOUNT);
        }
        for (NoticeKind kind : NoticeKind.values()) {
            assertThat(PreferenceRules.locked(kind)).as(kind.name())
                    .isEqualTo(kind.floor() || kind.category() == NoticeKind.Category.ACCOUNT);
        }
    }

    @Test
    void theChannelsAreReadByTheirCodes() {
        assertThat(PreferenceRules.Channel.fromCode("MAIL")).contains(PreferenceRules.Channel.MAIL);
        assertThat(PreferenceRules.Channel.fromCode("IN_APP")).contains(PreferenceRules.Channel.IN_APP);
        assertThat(PreferenceRules.Channel.fromCode("PUSH")).isEmpty();
    }
}
