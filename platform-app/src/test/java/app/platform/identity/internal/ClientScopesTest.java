package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The catalogue of scopes of connected applications (Sprint 31, ADR-0270), without a database: one list, held to the
 * scopes the closed table of public operations names, where a scope can only narrow and writing does not include
 * reading.
 */
class ClientScopesTest {

    @Test
    void theCatalogueIsTheThreeOperationScopesOfTheSprintThirtyTable() {
        assertThat(ClientScopes.keys()).containsExactly("objects.read", "records.read", "records.write");
    }

    @Test
    void theKeysAreExactlyTheScopesTheTableOfPublicOperationsNames() {
        Set<String> named = new TreeSet<>();
        PublicApiAccess.OPERATIONS.forEach(operation -> {
            if (operation.scope() != null) {
                named.add(operation.scope());
            }
        });

        assertThat(new TreeSet<>(ClientScopes.keys())).as("a scope cannot exist on one side alone")
                .isEqualTo(named);
    }

    @Test
    void everyScopeHasAnExplainingTitleAndDescription() {
        ClientScopes.ALL.forEach(scope -> {
            assertThat(scope.title()).as(scope.key()).isNotBlank();
            assertThat(scope.description()).as(scope.key()).isNotBlank().endsWith(".");
        });
    }

    @Test
    void writingDoesNotIncludeReadingAndTheWordsSayIt() {
        ClientScopes.Scope write = ClientScopes.ALL.stream().filter(scope -> "records.write".equals(scope.key()))
                .findFirst().orElseThrow();

        assertThat(write.description()).contains("Does not include reading");
        assertThat(PublicApiAccess.OPERATIONS.stream().filter(operation -> "createRecord".equals(operation.name()))
                .findFirst().orElseThrow().scope()).isEqualTo("records.write");
    }

    @Test
    void aRequestIsNormalisedWithoutRepeatsInTheOrderOfTheCatalogue() {
        assertThat(ClientScopes.normalize(List.of("records.write", "objects.read", "records.write", " records.read ")))
                .contains(List.of("objects.read", "records.read", "records.write"));
    }

    @Test
    void anEmptyRequestOrAScopeThatDoesNotExistIsNoRequest() {
        assertThat(ClientScopes.normalize(List.of())).isEmpty();
        assertThat(ClientScopes.normalize(null)).isEmpty();
        assertThat(ClientScopes.normalize(List.of("records.read", "records.admin"))).isEmpty();
        assertThat(ClientScopes.normalize(java.util.Arrays.asList("records.read", null))).isEmpty();
        assertThat(ClientScopes.normalize(List.of("Records.Read"))).as("keys are exact").isEmpty();
    }

    @Test
    void aTokenGetsTheRequestedScopesThatTheApplicationHoldsAndNeverMore() {
        Set<String> held = Set.of("records.read", "objects.read");

        assertThat(ClientScopes.narrowed(held, Set.of("records.read"))).containsExactly("records.read");
        assertThat(ClientScopes.narrowed(held, Set.of("records.read", "records.write")))
                .containsExactly("records.read");
        assertThat(ClientScopes.narrowed(held, Set.of("records.write"))).isEmpty();
        assertThat(ClientScopes.narrowed(Set.of(), Set.of("records.read"))).isEmpty();
    }
}
