package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules of a connected application that need no database (Sprint 31, ADR-0267, ADR-0269): what counts as
 * narrowing (it ends the tokens already issued), when an application runs, when a secret works, and that nothing prints
 * a hash.
 */
class ClientRulesTest {

    private static ClientStore.ClientRow row(String state, List<String> scopes, List<String> objects,
            String userStatus, String membershipStatus) {
        return new ClientStore.ClientRow(UUID.randomUUID(), "ca_abcdefghijklmnopqrstuvwx", "Name", "",
                UUID.randomUUID(), UUID.randomUUID(), state, scopes, objects, 900, 0,
                Instant.parse("2026-01-01T00:00:00Z"), "User A",
                userStatus, membershipStatus, 1, null);
    }

    private static ClientStore.ClientRow row(List<String> scopes, List<String> objects) {
        return row("ACTIVE", scopes, objects, "ACTIVE", "ACTIVE");
    }

    @Test
    void takingAScopeAwayNarrows() {
        ClientStore.ClientRow before = row(List.of("objects.read", "records.read"), null);

        assertThat(ClientRegistry.narrows(before, List.of("records.read"), null)).isTrue();
    }

    @Test
    void addingAScopeOrKeepingTheSameScopesDoesNotNarrow() {
        ClientStore.ClientRow before = row(List.of("records.read"), null);

        assertThat(ClientRegistry.narrows(before, List.of("records.read"), null)).isFalse();
        assertThat(ClientRegistry.narrows(before, List.of("records.read", "records.write"), null)).isFalse();
    }

    @Test
    void limitingAnApplicationThatHadNoListNarrows() {
        ClientStore.ClientRow before = row(List.of("records.read"), null);

        assertThat(ClientRegistry.narrows(before, List.of("records.read"), List.of("Employee__c"))).isTrue();
    }

    @Test
    void removingAnObjectFromTheListNarrowsAndAddingOneDoesNot() {
        ClientStore.ClientRow before = row(List.of("records.read"), List.of("Employee__c", "Vehicle__c"));

        assertThat(ClientRegistry.narrows(before, List.of("records.read"), List.of("Employee__c"))).isTrue();
        assertThat(ClientRegistry.narrows(before, List.of("records.read"),
                List.of("Employee__c", "Vehicle__c", "Asset__c"))).isFalse();
        assertThat(ClientRegistry.narrows(before, List.of("records.read"), List.of("Employee__c", "Vehicle__c")))
                .isFalse();
    }

    @Test
    void takingTheListAwayWidensAndDoesNotNarrow() {
        ClientStore.ClientRow before = row(List.of("records.read"), List.of("Employee__c"));

        assertThat(ClientRegistry.narrows(before, List.of("records.read"), null)).isFalse();
    }

    @Test
    void anApplicationRunsOnlyWhileItIsOnAndItsMemberCanStillAct() {
        assertThat(row("ACTIVE", List.of("records.read"), null, "ACTIVE", "ACTIVE").active()).isTrue();
        assertThat(row("ACTIVE", List.of("records.read"), null, "ACTIVE", "ACTIVE").memberCanAct()).isTrue();
        assertThat(row("DISABLED", List.of("records.read"), null, "ACTIVE", "ACTIVE").active()).isFalse();
        assertThat(row("ACTIVE", List.of("records.read"), null, "SUSPENDED", "ACTIVE").memberCanAct()).isFalse();
        assertThat(row("ACTIVE", List.of("records.read"), null, "ACTIVE", "DEACTIVATED").memberCanAct()).isFalse();
        assertThat(row("ACTIVE", List.of("records.read"), null, "DEACTIVATED", "DEACTIVATED").memberCanAct())
                .isFalse();
    }

    @Test
    void aSecretWorksUntilItIsEndedOrItsOverlapRunsOut() {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        UUID id = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        String hash = ClientSecrets.hash("cs_x");
        ClientStore.SecretRow plain = new ClientStore.SecretRow(id, client, 1, hash, null, null, null, null, now);
        ClientStore.SecretRow ending = new ClientStore.SecretRow(id, client, 1, hash, now.plusSeconds(3600), null, null,
                null, now);
        ClientStore.SecretRow over = new ClientStore.SecretRow(id, client, 1, hash, now.minusSeconds(1), null, null,
                null, now);
        ClientStore.SecretRow ended = new ClientStore.SecretRow(id, client, 1, hash, null, now.minusSeconds(5),
                "revoked", null, now);

        assertThat(plain.works(now)).isTrue();
        assertThat(ending.works(now)).isTrue();
        assertThat(ending.works(now.plusSeconds(3601))).isFalse();
        assertThat(over.works(now)).isFalse();
        assertThat(ended.works(now)).isFalse();
    }

    @Test
    void aSecretRowNeverPrintsItsHash() {
        String hash = ClientSecrets.hash(ClientSecrets.generate());
        ClientStore.SecretRow secret = new ClientStore.SecretRow(UUID.randomUUID(), UUID.randomUUID(), 1, hash, null,
                null, null, null, Instant.now());

        assertThat(secret.toString()).doesNotContain(hash).doesNotContain("sha256");
    }

}
