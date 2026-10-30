package app.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretMetadata;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretStoreException;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The contract every implementation of {@link SecretsStore} must pass (ADR-0007, made reusable by Sprint 27, ADR-0214):
 * isolation between organizations, versions, no readback beyond the one resolve, a deletion that is complete and
 * harmless when repeated, and a value that is a copy. An implementation extends this class and says how to get a store
 * and a new organization.
 */
public abstract class SecretsStoreContract {

    /** The store under test. */
    protected abstract SecretsStore store();

    /** A new organization (one that the store can hold secrets for). */
    protected abstract TenantId newTenant();

    /** Whether the store holds secrets of the platform's own scope; if not it must refuse them, never ignore them. */
    protected boolean holdsPlatformSecrets() {
        return true;
    }

    /** A new test class instance is made for every test, so these names never meet in a shared database. */
    private final String suffix = "-" + java.util.UUID.randomUUID().toString().substring(0, 6);

    private SecretRef ref(TenantId tenant, String name) {
        return SecretRef.forTenant(tenant, name + suffix);
    }

    private static String text(Optional<SecretValue> value) {
        SecretValue found = value.orElseThrow();
        return new String(found.reveal());
    }

    @Test
    void resolvesWhatWasPut() {
        SecretRef ref = ref(newTenant(), "contract-key");

        store().put(ref, SecretValue.of("first value"));

        assertThat(text(store().resolve(ref))).isEqualTo("first value");
    }

    @Test
    void aMissingSecretResolvesToNothingAndIsNotAnError() {
        assertThat(store().resolve(ref(newTenant(), "never-written"))).isEmpty();
        assertThat(store().describe(ref(newTenant(), "never-written"))).isEmpty();
    }

    @Test
    void everyPutMakesTheNextVersionAndResolveGivesTheCurrentOne() {
        SecretRef ref = ref(newTenant(), "rotating-key");

        SecretMetadata first = store().put(ref, SecretValue.of("v1"));
        SecretMetadata second = store().put(ref, SecretValue.of("v2"));
        SecretMetadata third = store().put(ref, SecretValue.of("v3"));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(third.version()).isEqualTo(3);
        assertThat(text(store().resolve(ref))).isEqualTo("v3");
        assertThat(store().describe(ref)).get().extracting(SecretMetadata::version).isEqualTo(3);
    }

    @Test
    void describeGivesFactsOnlyAndTheCreationNeverMovesWhenTheValueIsReplaced() {
        SecretRef ref = ref(newTenant(), "dated-key");
        SecretMetadata first = store().put(ref, SecretValue.of("canary-describe-1"));
        SecretMetadata second = store().put(ref, SecretValue.of("canary-describe-2"));

        SecretMetadata described = store().describe(ref).orElseThrow();

        assertThat(described.ref()).isEqualTo(ref);
        assertThat(described.createdAt()).isEqualTo(first.createdAt());
        assertThat(described.updatedAt()).isAfterOrEqualTo(described.createdAt());
        assertThat(second.version()).isEqualTo(described.version());
        assertThat(described.toString()).doesNotContain("canary");
    }

    @Test
    void theSameNameInTwoOrganizationsIsTwoSecrets() {
        TenantId a = newTenant();
        TenantId b = newTenant();

        store().put(ref(a, "shared-name"), SecretValue.of("value of a"));
        store().put(ref(b, "shared-name"), SecretValue.of("value of b"));

        assertThat(text(store().resolve(ref(a, "shared-name")))).isEqualTo("value of a");
        assertThat(text(store().resolve(ref(b, "shared-name")))).isEqualTo("value of b");
    }

    @Test
    void anotherOrganizationNeverFindsASecret() {
        TenantId owner = newTenant();
        TenantId other = newTenant();
        store().put(ref(owner, "private-key"), SecretValue.of("canary-isolated"));

        assertThat(store().resolve(ref(other, "private-key"))).isEmpty();
        assertThat(store().describe(ref(other, "private-key"))).isEmpty();
    }

    @Test
    void deletingOneOrganizationsSecretLeavesTheSameNameOfAnotherAlone() {
        TenantId a = newTenant();
        TenantId b = newTenant();
        store().put(ref(a, "gone-key"), SecretValue.of("a"));
        store().put(ref(b, "gone-key"), SecretValue.of("b"));

        store().delete(ref(a, "gone-key"));

        assertThat(store().resolve(ref(a, "gone-key"))).isEmpty();
        assertThat(text(store().resolve(ref(b, "gone-key")))).isEqualTo("b");
    }

    @Test
    void deletingRemovesEveryVersionAndDeletingAMissingSecretIsNotAnError() {
        SecretRef ref = ref(newTenant(), "deleted-key");
        store().put(ref, SecretValue.of("v1"));
        store().put(ref, SecretValue.of("v2"));

        store().delete(ref);
        store().delete(ref);

        assertThat(store().resolve(ref)).isEmpty();
        assertThat(store().describe(ref)).isEmpty();
        assertThat(store().put(ref, SecretValue.of("again")).version()).isEqualTo(1);
    }

    @Test
    void aResolvedValueIsACopyOfWhatIsStored() {
        SecretRef ref = ref(newTenant(), "copy-key");
        store().put(ref, SecretValue.of("stays whole"));

        SecretValue first = store().resolve(ref).orElseThrow();
        first.destroy();

        assertThat(text(store().resolve(ref))).isEqualTo("stays whole");
    }

    @Test
    void theValueTheCallerGaveIsNotKeptByReference() {
        SecretRef ref = ref(newTenant(), "given-key");
        SecretValue given = SecretValue.of("given value");

        store().put(ref, given);
        given.destroy();

        assertThat(text(store().resolve(ref))).isEqualTo("given value");
    }

    @Test
    void anyTextRoundTripsExactly() {
        SecretRef ref = ref(newTenant(), "text-key");
        String awkward = "  leading and trailing  \n\ttabs ü ß 日本語 🔑 \" ' \\ " + "x".repeat(3000);

        store().put(ref, SecretValue.of(awkward));

        assertThat(text(store().resolve(ref))).isEqualTo(awkward);
    }

    @Test
    void whatTheStoreHandsOutPrintsRedacted() {
        SecretRef ref = ref(newTenant(), "print-key");
        store().put(ref, SecretValue.of("canary-printed"));

        SecretValue value = store().resolve(ref).orElseThrow();

        assertThat(value.toString()).doesNotContain("canary");
        assertThat(String.valueOf(store().describe(ref).orElseThrow())).doesNotContain("canary");
    }

    @Test
    void theScopeOfThePlatformIsHeldSeparatelyOrRefusedAndNeverTreatedAsAnOrganization() {
        SecretRef platform = SecretRef.forPlatform("platform-wide-key" + suffix);
        if (holdsPlatformSecrets()) {
            store().put(platform, SecretValue.of("platform value"));
            store().put(ref(newTenant(), "platform-wide-key"), SecretValue.of("tenant value"));

            assertThat(text(store().resolve(platform))).isEqualTo("platform value");
        } else {
            assertThatThrownBy(() -> store().put(platform, SecretValue.of("platform value")))
                    .isInstanceOf(SecretStoreException.class);
            assertThatThrownBy(() -> store().resolve(platform)).isInstanceOf(SecretStoreException.class);
        }
    }
}
