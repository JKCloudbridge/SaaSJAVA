package app.platform.sharedkernel.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventTypesTest {

    @Test
    void acceptsDottedLowerCaseTypes() {
        assertThat(new NewEvent("tenant.suspended", "{}").type()).isEqualTo("tenant.suspended");
        assertThat(new NewEvent("record.field_changed.v2", "{}").type()).isEqualTo("record.field_changed.v2");
    }

    @Test
    void rejectsTypesThatAreNotDottedLowerCaseWords() {
        for (String bad : new String[] {"", "tenant", "Tenant.Suspended", "tenant..suspended", ".tenant.x",
            "tenant.suspended.", "tenant suspended", "tenant.1x", "x".repeat(101) + ".y"}) {
            assertThatThrownBy(() -> new NewEvent(bad, "{}")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsAnOversizedPayload() {
        assertThatThrownBy(() -> new NewEvent("tenant.suspended", "x".repeat(NewEvent.MAX_PAYLOAD_CHARACTERS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEnvelopeExposesTheOptionalContextAsOptionals() {
        UUID user = UUID.randomUUID();
        EventEnvelope withUser = new EventEnvelope(UUID.randomUUID(), new TenantId(UUID.randomUUID()), user, null,
                "tenant.suspended", "{}", Instant.now(), 1);

        assertThat(withUser.user()).contains(user);
        assertThat(withUser.membership()).isEmpty();
        assertThatThrownBy(() -> new EventEnvelope(UUID.randomUUID(), new TenantId(UUID.randomUUID()), null, null,
                "tenant.suspended", "{}", Instant.now(), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
