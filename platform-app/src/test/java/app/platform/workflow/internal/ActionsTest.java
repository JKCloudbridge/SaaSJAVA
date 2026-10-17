package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The closed seam of actions (ADR-0136, ADR-0137): the registry refuses to start when the types and the executors
 * differ, and the one action of this release announces an event that names the record and carries no value.
 */
class ActionsTest {

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

    @Test
    void theRegistryStartsWhenEveryTypeHasOneExecutor() {
        ActionRegistry registry = new ActionRegistry(List.of(new PublishEventAction(new Published())));

        assertThat(registry.find("PUBLISH_EVENT")).isPresent();
        assertThat(registry.find("SEND_EMAIL")).as("a type of a later release").isEmpty();
    }

    @Test
    void theRegistryRefusesToStartWhenATypeHasNoExecutor() {
        assertThatThrownBy(() -> new ActionRegistry(List.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void theRegistryRefusesToStartWhenAnExecutorHasNoType() {
        assertThatThrownBy(() -> new ActionRegistry(List.of(new PublishEventAction(new Published()),
                executorOf("DELETE_EVERYTHING")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void theRegistryRefusesTwoExecutorsOfOneType() {
        assertThatThrownBy(() -> new ActionRegistry(List.of(new PublishEventAction(new Published()),
                executorOf("PUBLISH_EVENT")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two executors");
    }

    @Test
    void everyTypeHasASchemaWithItsSettings() {
        assertThat(WorkflowActionTypes.all()).extracting(WorkflowActionTypes.Type::type)
                .containsExactly("PUBLISH_EVENT");
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
    void theKeyForAnythingDoneOutsideTheDatabaseIsOnePerExecutionAndStep() {
        UUID execution = UUID.randomUUID();
        ActionExecutor.ActionContext first = new ActionExecutor.ActionContext(execution, "W__c", "O__c",
                UUID.randomUUID(), null, 1, "A", Map.of());
        ActionExecutor.ActionContext second = new ActionExecutor.ActionContext(execution, "W__c", "O__c",
                UUID.randomUUID(), null, 2, "B", Map.of());

        assertThat(first.idempotencyKey()).isEqualTo(execution + ":1").isNotEqualTo(second.idempotencyKey());
    }
}
