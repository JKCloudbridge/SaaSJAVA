package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.WorkflowDefinition;
import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platformapi.WorkflowActionTypes;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The closed seam of actions (ADR-0136, ADR-0137, ADR-0138): the registry refuses to start when the types and the
 * executors differ, every type has a schema with the settings it takes, the key of an effect outside the database is
 * stable across the retries of one execution and differs across a replay, and the event action announces an event that
 * names the record and carries no value.
 */
class ActionsTest {

    private static final List<String> TYPES = List.of("UPDATE_RECORD", "CREATE_RECORD", "SEND_EMAIL",
            "START_APPROVAL", "PUBLISH_EVENT");

    /** Keeps the events it was asked to publish. */
    private static final class Published implements EventPublisher {

        private final List<NewEvent> events = new ArrayList<>();

        @Override
        public void publish(NewEvent event) {
            events.add(event);
        }
    }

    private static ActionExecutor executorOf(String type) {
        return new ActionExecutor() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void execute(ActionContext context) {
                // Nothing: only the registry is looked at.
            }
        };
    }

    private static List<ActionExecutor> executorsOf(String... types) {
        return java.util.Arrays.stream(types).map(ActionsTest::executorOf).toList();
    }

    @Test
    void theRegistryStartsWhenEveryTypeHasOneExecutor() {
        ActionRegistry registry = new ActionRegistry(executorsOf(TYPES.toArray(String[]::new)));

        TYPES.forEach(type -> assertThat(registry.find(type)).as(type).isPresent());
        assertThat(registry.find("CALL_INTEGRATION")).as("a type of a later release").isEmpty();
    }

    @Test
    void theRegistryRefusesToStartWhenATypeHasNoExecutor() {
        assertThatThrownBy(() -> new ActionRegistry(List.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
        assertThatThrownBy(() -> new ActionRegistry(executorsOf("UPDATE_RECORD", "CREATE_RECORD", "PUBLISH_EVENT")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("differ");
    }

    @Test
    void theRegistryRefusesToStartWhenAnExecutorHasNoType() {
        List<ActionExecutor> executors = new ArrayList<>(executorsOf(TYPES.toArray(String[]::new)));
        executors.add(executorOf("DELETE_EVERYTHING"));

        assertThatThrownBy(() -> new ActionRegistry(executors)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void theRegistryRefusesTwoExecutorsOfOneType() {
        List<ActionExecutor> executors = new ArrayList<>(executorsOf(TYPES.toArray(String[]::new)));
        executors.add(executorOf("PUBLISH_EVENT"));

        assertThatThrownBy(() -> new ActionRegistry(executors)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two executors");
    }

    @Test
    void everyTypeHasASchemaWithItsSettings() {
        assertThat(WorkflowActionTypes.all()).extracting(WorkflowActionTypes.Type::type)
                .containsExactlyElementsOf(TYPES);
        assertThat(WorkflowActionTypes.find("PUBLISH_EVENT").orElseThrow().settings()).singleElement()
                .satisfies(setting -> {
                    assertThat(setting.key()).isEqualTo("event");
                    assertThat(setting.required()).isTrue();
                    assertThat(setting.shape().matcher("order_review.requested").matches()).isTrue();
                    assertThat(setting.shape().matcher("Order Review").matches()).isFalse();
                    assertThat(setting.shape().matcher("a.b.c.d.e.f").matches()).isFalse();
                });
    }

    @Test
    void theActionsThatChangeRecordsTakeAssignmentsAndTheOthersDoNot() {
        assertThat(WorkflowActionTypes.find("UPDATE_RECORD").orElseThrow().assignments())
                .isEqualTo(WorkflowActionTypes.Assignments.TRIGGERING_OBJECT);
        assertThat(WorkflowActionTypes.find("UPDATE_RECORD").orElseThrow().settings()).isEmpty();
        assertThat(WorkflowActionTypes.find("CREATE_RECORD").orElseThrow().assignments())
                .isEqualTo(WorkflowActionTypes.Assignments.NAMED_OBJECT);
        assertThat(WorkflowActionTypes.find("CREATE_RECORD").orElseThrow().settings())
                .extracting(WorkflowActionTypes.Setting::key).containsExactly("object", "linkField");
        assertThat(WorkflowActionTypes.find("SEND_EMAIL").orElseThrow().assignments())
                .isEqualTo(WorkflowActionTypes.Assignments.NONE);
        assertThat(WorkflowActionTypes.find("PUBLISH_EVENT").orElseThrow().assignments())
                .isEqualTo(WorkflowActionTypes.Assignments.NONE);
    }

    @Test
    void theMailTakesOneClosedTemplateAndTheNameOfAField() {
        WorkflowActionTypes.Type mail = WorkflowActionTypes.find("SEND_EMAIL").orElseThrow();

        assertThat(mail.settings()).extracting(WorkflowActionTypes.Setting::key)
                .containsExactly("recipientField", "template");
        WorkflowActionTypes.Setting template = mail.settings().get(1);
        assertThat(template.allowed()).containsExactly("RECORD_NOTICE");
        assertThat(template.shape().matcher("RECORD_NOTICE").matches()).isTrue();
        assertThat(template.shape().matcher("SIGN_UP_REQUEST").matches()).isFalse();
    }

    @Test
    void theEventActionAnnouncesANamedEventWithIdentifiersAndNoValue() {
        Published published = new Published();
        UUID execution = UUID.randomUUID();
        UUID record = UUID.randomUUID();

        new PublishEventAction(published).execute(new ActionExecutor.ActionContext(execution, "Review__c", "Order__c",
                record, 7L, 1, "Announce", Map.of("event", "order.created")));

        assertThat(published.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("workflow.signal.order.created");
            assertThat(JsonPath.<String>read(event.payload(), "$.object")).isEqualTo("Order__c");
            assertThat(JsonPath.<String>read(event.payload(), "$.recordId")).isEqualTo(record.toString());
            assertThat(JsonPath.<String>read(event.payload(), "$.workflow")).isEqualTo("Review__c");
            assertThat(JsonPath.<String>read(event.payload(), "$.execution")).isEqualTo(execution.toString());
            assertThat(JsonPath.<String>read(event.payload(), "$.step")).isEqualTo("Announce");
            assertThat(JsonPath.<Map<String, Object>>read(event.payload(), "$")).containsOnlyKeys("object",
                    "recordId", "workflow", "execution", "step");
        });
    }

    @Test
    void theKeyForAnythingDoneOutsideTheDatabaseIsOnePerExecutionStepAndReplayGeneration() {
        UUID execution = UUID.randomUUID();
        ActionExecutor.ActionContext first = new ActionExecutor.ActionContext(execution, "W__c", "O__c",
                UUID.randomUUID(), null, 1, "A", Map.of());
        ActionExecutor.ActionContext second = new ActionExecutor.ActionContext(execution, "W__c", "O__c",
                UUID.randomUUID(), null, 2, "B", Map.of());
        ActionExecutor.ActionContext replayed = new ActionExecutor.ActionContext(execution, UUID.randomUUID(), "W__c",
                "O__c", UUID.randomUUID(), null, 1, "A", Map.of(), List.<WorkflowDefinition.Assignment>of(),
                Chain.none(), 1);

        assertThat(first.idempotencyKey()).isEqualTo(execution + ":1:0").isNotEqualTo(second.idempotencyKey());
        assertThat(replayed.idempotencyKey()).as("a replay acts again, on purpose (ADR-0141)")
                .isEqualTo(execution + ":1:1").isNotEqualTo(first.idempotencyKey());
    }

    @Test
    void theKeyIsTheSameOnEveryRetryOfOneExecution() {
        UUID execution = UUID.randomUUID();
        Chain chain = Chain.none();
        ActionExecutor.ActionContext attemptOne = new ActionExecutor.ActionContext(execution, UUID.randomUUID(),
                "W__c", "O__c", UUID.randomUUID(), 3L, 2, "B", Map.of(), List.of(), chain, 0);
        ActionExecutor.ActionContext attemptTwo = new ActionExecutor.ActionContext(execution, attemptOne.workflowId(),
                "W__c", "O__c", attemptOne.recordId(), 3L, 2, "B", Map.of(), List.of(), chain, 0);

        assertThat(attemptTwo.idempotencyKey()).isEqualTo(attemptOne.idempotencyKey());
    }

    @Test
    void theSourceOfAWriteCarriesTheChainIncludingTheExecutionThatWrites() {
        UUID execution = UUID.randomUUID();
        UUID workflow = UUID.randomUUID();
        UUID earlier = UUID.randomUUID();
        Chain chain = new Chain(UUID.randomUUID(), 1, List.of(earlier)).extendedBy(workflow, execution);

        ActionExecutor.ActionContext context = new ActionExecutor.ActionContext(execution, workflow, "W__c", "O__c",
                UUID.randomUUID(), null, 1, "A", Map.of(), List.of(), chain, 0);

        assertThat(context.source().chain().depth()).isEqualTo(2);
        assertThat(context.source().chain().workflows()).containsExactly(earlier, workflow);
        assertThat(context.source().workflow()).isEqualTo("W__c");
        assertThat(context.source().execution()).isEqualTo(execution);
    }
}
