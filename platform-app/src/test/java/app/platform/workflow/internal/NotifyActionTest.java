package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platformapi.WorkflowActionTypes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The action that tells a member in the application (ADR-0161): one notice about the triggering record, to the owner or
 * to the person a user field names, with facts only, once across a retry (the key is the idempotency key of the step),
 * and with stable permanent reasons when it cannot.
 */
class NotifyActionTest {

    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID EXECUTION = UUID.randomUUID();

    private final BackgroundRecords records = mock(BackgroundRecords.class);
    private final Metadata metadata = mock(Metadata.class);
    private final Notices notices = mock(Notices.class);
    private final ReferenceDirectory members = mock(ReferenceDirectory.class);
    private final NotifyAction action = notifyAction();

    private NotifyAction notifyAction() {
        // The action indexes the directories by the object they serve when it is built, so the name comes first.
        when(members.object()).thenReturn("User");
        return new NotifyAction(records, metadata, notices, List.of(members));
    }

    private static FieldDefinition lookupToUser(String name) {
        return new FieldDefinition("Order__c", name, name, "", DefinitionKind.CUSTOM, FieldType.LOOKUP, false, false,
                null, new ReferenceConfiguration("User"), false, 0);
    }

    private static FieldDefinition plain(String name) {
        return new FieldDefinition("Order__c", name, name, "", DefinitionKind.CUSTOM, FieldType.BOOLEAN, false, false,
                null, new NoConfiguration(), false, 0);
    }

    private void world(Map<String, Object> fields) {
        when(members.object()).thenReturn("User");
        when(metadata.object("Order__c")).thenReturn(Optional.of(new ObjectDefinition("Order__c", "Order", "Orders",
                "", DefinitionKind.CUSTOM, null, true, 0, List.of(lookupToUser("manager__c"), plain("flag__c")))));
        when(records.find("Order__c", RECORD)).thenReturn(Optional.of(new DataRecord(RECORD, "Order__c", null, OWNER,
                fields, 1, Instant.now(), OWNER, Instant.now(), OWNER, null, null)));
        when(members.exists(any())).thenReturn(true);
    }

    private static ActionExecutor.ActionContext context(Map<String, String> settings, int generation) {
        return new ActionExecutor.ActionContext(EXECUTION, UUID.randomUUID(), "Remind__c", "Order__c", RECORD, 1L, 2,
                "Tell", settings, List.of(), Chain.none(), generation);
    }

    @Test
    void itIsTheTypeTheCatalogueCallsNotify() {
        assertThat(action.type()).isEqualTo(WorkflowActionTypes.NOTIFY);
    }

    @Test
    void itTellsTheOwnerWithFactsOnlyAndTheKeyOfTheStep() {
        world(Map.of("flag__c", true));

        action.execute(context(Map.of("recipient", "OWNER"), 0));

        ArgumentCaptor<NoticeRequest> sent = ArgumentCaptor.forClass(NoticeRequest.class);
        verify(notices).notify(sent.capture());
        NoticeRequest request = sent.getValue();
        assertThat(request.kind()).isEqualTo(NoticeKind.WORKFLOW_NOTICE);
        assertThat(request.recipientId()).isEqualTo(OWNER);
        assertThat(request.objectApiName()).isEqualTo("Order__c");
        assertThat(request.recordId()).isEqualTo(RECORD);
        assertThat(request.facts()).containsOnly(Map.entry("workflow", "Remind__c"));
        assertThat(request.dedupeKey()).isEqualTo("wf:" + EXECUTION + ":2:0");
    }

    @Test
    void itTellsThePersonAUserFieldNames() {
        world(Map.of("manager__c", MANAGER.toString()));

        action.execute(context(Map.of("recipient", "FIELD", "recipientField", "manager__c"), 0));

        ArgumentCaptor<NoticeRequest> sent = ArgumentCaptor.forClass(NoticeRequest.class);
        verify(notices).notify(sent.capture());
        assertThat(sent.getValue().recipientId()).isEqualTo(MANAGER);
    }

    @Test
    void aRetryMakesTheSameRequestAndAReplayANewOne() {
        world(Map.of());

        action.execute(context(Map.of("recipient", "OWNER"), 0));
        action.execute(context(Map.of("recipient", "OWNER"), 0));
        action.execute(context(Map.of("recipient", "OWNER"), 1));

        ArgumentCaptor<NoticeRequest> sent = ArgumentCaptor.forClass(NoticeRequest.class);
        verify(notices, org.mockito.Mockito.times(3)).notify(sent.capture());
        assertThat(sent.getAllValues().get(0).dedupeKey()).isEqualTo(sent.getAllValues().get(1).dedupeKey());
        assertThat(sent.getAllValues().get(2).dedupeKey()).isNotEqualTo(sent.getAllValues().get(0).dedupeKey());
    }

    @Test
    void whenItCannotItFailsWithAStablePermanentReasonAndTellsNobody() {
        world(Map.of());
        when(records.find("Order__c", RECORD)).thenReturn(Optional.empty());
        assertThat(reasonOf(Map.of("recipient", "OWNER"))).isEqualTo("record_gone");

        world(Map.of());
        assertThat(reasonOf(Map.of("recipient", "FIELD", "recipientField", "manager__c")))
                .isEqualTo("recipient_missing");
        assertThat(reasonOf(Map.of("recipient", "FIELD", "recipientField", "gone__c")))
                .isEqualTo("recipient_field_gone");

        world(Map.of("manager__c", MANAGER.toString()));
        when(members.exists(MANAGER)).thenReturn(false);
        assertThat(reasonOf(Map.of("recipient", "FIELD", "recipientField", "manager__c")))
                .isEqualTo("recipient_not_member");

        world(Map.of("manager__c", "not an identifier"));
        assertThat(reasonOf(Map.of("recipient", "FIELD", "recipientField", "manager__c")))
                .isEqualTo("recipient_not_member");

        when(metadata.object("Order__c")).thenReturn(Optional.empty());
        assertThat(reasonOf(Map.of("recipient", "OWNER"))).isEqualTo("object_unavailable");
        verify(notices, never()).notify(any());
    }

    private String reasonOf(Map<String, String> settings) {
        try {
            action.execute(context(settings, 0));
        } catch (StepFailure failure) {
            assertThat(failure.isTransient()).as("no retry can mend " + failure.reason()).isFalse();
            return failure.reason();
        }
        throw new AssertionError("The action should have failed");
    }

    @Test
    void theRecipientMustStillBeAnActiveMemberAndTheDirectoryIsAskedForIt() {
        world(Map.of());
        when(members.exists(OWNER)).thenReturn(false);

        assertThatThrownBy(() -> action.execute(context(Map.of("recipient", "OWNER"), 0)))
                .isInstanceOf(StepFailure.class).hasMessage("recipient_not_member");
    }
}
