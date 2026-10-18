package app.platform.sharedkernel.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoticeRequestTest {

    private static final UUID PERSON = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RECORD = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void aRequestHoldsTheKindTheRecordTheFactsAndTheKey() {
        NoticeRequest request = new NoticeRequest(NoticeKind.APPROVAL_TASK, PERSON, "Expense__c", RECORD,
                Map.of("process", "Large__c", "step", "Manager"), "apv-task:" + RECORD + ":" + PERSON);

        assertThat(request.kind()).isEqualTo(NoticeKind.APPROVAL_TASK);
        assertThat(request.facts()).containsEntry("process", "Large__c").containsEntry("step", "Manager");
    }

    @Test
    void aFactTheKindDoesNotListIsRefused() {
        assertThatThrownBy(() -> new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, null, null,
                Map.of("process", "Large__c"), "k")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFactIsAnApiNameOrAStableKeyNeverText() {
        for (String value : new String[] {"two words", "a sentence, with a comma", "https://x.example.test/", "a<b",
            "", "x".repeat(81), "line\nbreak"}) {
            assertThatThrownBy(() -> new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, null, null,
                    Map.of("workflow", value), "k")).as(value).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theRecordIsNamedWithItsObjectOrNotAtAll() {
        assertThatThrownBy(() -> new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, "Expense__c", null, Map.of(),
                "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, null, RECORD, Map.of(), "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, "not an object", RECORD,
                Map.of(), "k")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new NoticeRequest(NoticeKind.TRIAL_NOTICE, PERSON, null, null, Map.of("stage", "first"), "k"))
                .isNotNull();
    }

    @Test
    void theKeyIsShapedAndBounded() {
        assertThatThrownBy(() -> new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, null, null, Map.of(), "a b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, null, null, Map.of(),
                "k".repeat(161))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itPrintsTheKindOnlyBecauseANoticeIsAboutAPerson() {
        NoticeRequest request = new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, PERSON, null, null, Map.of(), "k");

        assertThat(request.toString()).isEqualTo("NoticeRequest[WORKFLOW_NOTICE]").doesNotContain(PERSON.toString());
    }

    @Test
    void theCategoriesAndTheFloorAreAsTheDecisionsSay() {
        assertThat(NoticeKind.TRIAL_NOTICE.category()).isEqualTo(NoticeKind.Category.ACCOUNT);
        assertThat(NoticeKind.TRIAL_NOTICE.floor()).isTrue();
        for (NoticeKind kind : NoticeKind.values()) {
            if (kind != NoticeKind.TRIAL_NOTICE) {
                assertThat(kind.floor()).as(kind.name()).isFalse();
                assertThat(kind.category()).isNotEqualTo(NoticeKind.Category.ACCOUNT);
            }
        }
        assertThat(NoticeKind.fromCode("WORKFLOW_NOTICE")).contains(NoticeKind.WORKFLOW_NOTICE);
        assertThat(NoticeKind.fromCode("NOPE")).isEmpty();
    }
}
