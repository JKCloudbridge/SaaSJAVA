package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.TestSecrets;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.Transport;
import app.platform.integration.adapter.file.FileTransferConnector;
import app.platform.integration.internal.ConnectionStore.Definition;
import app.platform.integration.internal.CredentialLogins.Login;
import app.platform.integration.internal.CredentialLogins.Resolved;
import app.platform.integration.internal.EndpointRules.Endpoint;
import app.platform.integration.internal.EndpointRules.Policy;
import app.platform.integration.internal.EndpointRules.Refusal;
import app.platform.integration.internal.EndpointRules.Result;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * The address of a file server, the settings only a file server has, the limits of picking files up, and the sign-in of
 * a file transfer connection (Sprint 29A, ADR-0237, ADR-0239, ADR-0240). No database and no network: the address rules
 * are pure, and the sign-in is built from a store in memory.
 */
class FileAddressAndSettingsTest {

    private static final Policy NONE = new Policy(Set.of());
    private static final Policy DEVELOPMENT = new Policy(Set.of("127.0.0.1"));
    private static final Path CORPUS = Path.of("src/test/resources/integration/hostile-addresses.txt");
    private static final ConnectorDescription DESCRIPTION = new FileTransferConnector().describe();
    private static final String KEY = "SHA256:" + "A".repeat(43);

    // ---- the address ----

    @Test
    void aFileAddressIsSftpHostAndAnOptionalPortAndNothingElse() {
        Endpoint plain = EndpointRules.parse("sftp://files.example.com", NONE, Transport.SFTP).endpoint();
        Endpoint explicit = EndpointRules.parse("SFTP://Files.Example.com:22/", NONE, Transport.SFTP).endpoint();
        Endpoint other = EndpointRules.parse("sftp://files.example.com:2222", NONE, Transport.SFTP).endpoint();
        Endpoint literal = EndpointRules.parse("sftp://8.8.8.8", NONE, Transport.SFTP).endpoint();
        Endpoint v6 = EndpointRules.parse("sftp://[2606:4700::1111]:2200", NONE, Transport.SFTP).endpoint();

        assertThat(plain.port()).isEqualTo(22);
        assertThat(plain.scheme()).isEqualTo("sftp");
        assertThat(plain.secure()).as("a file transfer is encrypted").isTrue();
        assertThat(plain.development()).isFalse();
        assertThat(DefinitionChecks.canonical(plain)).isEqualTo("sftp://files.example.com");
        assertThat(DefinitionChecks.canonical(explicit)).as("the default port is not written")
                .isEqualTo("sftp://files.example.com");
        assertThat(DefinitionChecks.canonical(other)).isEqualTo("sftp://files.example.com:2222");
        assertThat(DefinitionChecks.canonical(literal)).isEqualTo("sftp://8.8.8.8");
        assertThat(DefinitionChecks.canonical(v6)).isEqualTo("sftp://[2606:4700::1111]:2200");
        assertThat(other.basePath()).isEmpty();
    }

    @Test
    void aWebAddressIsUnchangedAndAFileAddressIsNotAWebAddress() {
        Result web = EndpointRules.parse("https://api.example.com:8443/v1", NONE);
        assertThat(web.endpoint().scheme()).isEqualTo("https");
        assertThat(web.endpoint().port()).isEqualTo(8443);
        assertThat(DefinitionChecks.canonical(web.endpoint())).isEqualTo("https://api.example.com:8443/v1");
        assertThat(EndpointRules.parse("https://api.example.com", NONE, Transport.HTTP).endpoint().port())
                .isEqualTo(443);

        assertThat(EndpointRules.parse("sftp://files.example.com", NONE).refusal()).isEqualTo(Refusal.SCHEME);
        assertThat(EndpointRules.parse("https://files.example.com", NONE, Transport.SFTP).refusal())
                .isEqualTo(Refusal.SCHEME_SFTP);
        assertThat(EndpointRules.parse("http://files.example.com", NONE, Transport.SFTP).refusal())
                .isEqualTo(Refusal.SCHEME_SFTP);
        assertThat(EndpointRules.parse("files.example.com", NONE, Transport.SFTP).refusal())
                .isEqualTo(Refusal.SCHEME_SFTP);
        assertThat(Refusal.SCHEME_SFTP.words()).contains("sftp://").doesNotContain("https");
    }

    @Test
    void aFileAddressNamesNoUserNoPathNoQueryAndNoPrivateHost() {
        for (String address : List.of("sftp://user-a@files.example.com", "sftp://user-a:pw@files.example.com",
                "sftp://files.example.com/inbox", "sftp://files.example.com/..", "sftp://files.example.com?x=1",
                "sftp://files.example.com#x", "sftp://files.example.com:0", "sftp://files.example.com:65536",
                "sftp://files.example.com:-1", "sftp://files.example.com:2a", "sftp://localhost",
                "sftp://127.0.0.1", "sftp://10.0.0.1", "sftp://192.168.1.1", "sftp://169.254.169.254",
                "sftp://[::1]", "sftp://files.internal", "sftp://files.local", "sftp://2130706433",
                "sftp://0x7f.0.0.1", "sftp://", "sftp://:22")) {
            Result result = EndpointRules.parse(address, NONE, Transport.SFTP);

            assertThat(result.ok()).as(address).isFalse();
            assertThat(result.refusal().words()).as(address).doesNotContain("user-a").doesNotContain("inbox");
        }
    }

    @Test
    void aDevelopmentHostMayBeReachedByFileTransferAndNoOtherPrivateHostMay() {
        Result local = EndpointRules.parse("sftp://127.0.0.1:2222", DEVELOPMENT, Transport.SFTP);

        assertThat(local.ok()).isTrue();
        assertThat(local.endpoint().development()).isTrue();
        assertThat(local.endpoint().scheme()).isEqualTo("sftp");
        assertThat(EndpointRules.parse("sftp://127.0.0.2:2222", DEVELOPMENT, Transport.SFTP).ok()).isFalse();
        assertThat(EndpointRules.parse("sftp://10.0.0.1", DEVELOPMENT, Transport.SFTP).ok()).isFalse();
    }

    @Test
    void everyWebAddressTheRulesRefuseIsRefusedAsAFileAddressToo() throws IOException {
        int tried = 0;
        for (String line : Files.readAllLines(CORPUS, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String address = line.substring(line.indexOf('|') + 1).stripLeading();
            if (!address.startsWith("https://")) {
                continue;
            }
            String file = "sftp://" + address.substring("https://".length());

            Result result = EndpointRules.parse(file, NONE, Transport.SFTP);

            assertThat(result.ok()).as("must be refused: " + line).isFalse();
            tried++;
        }
        assertThat(tried).as("the corpus is read").isGreaterThan(40);
    }

    // ---- the settings only a file server has ----

    @Test
    void theFingerprintOfAKeyIsWrittenTheWayTheUsualToolsPrintIt() {
        for (String good : List.of(KEY, "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU")) {
            assertThat(SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", good)).ok()).as(good).isTrue();
        }
        for (String bad : List.of("SHA256:" + "A".repeat(42), "SHA256:" + "A".repeat(44), "sha256:" + "A".repeat(43),
                "SHA256:" + "A".repeat(42) + "=", "SHA256:" + "A".repeat(42) + "!", "MD5:" + "a".repeat(32),
                "SHA256: " + "A".repeat(43))) {
            SettingsValidator.Result result = SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", bad));

            assertThat(result.problems()).as(bad).containsKey("settings.hostKey");
            assertThat(result.problems().toString()).as("never repeated").doesNotContain("MD5").doesNotContain("!");
        }
        assertThat(SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", KEY + "\n")).values().get("hostKey"))
                .as("a pasted line end is stripped before the key is kept").isEqualTo(KEY);
        assertThat(SettingsValidator.validate(DESCRIPTION, Map.of()).problems()).as("no key, no connection")
                .containsKey("settings.hostKey");
    }

    @Test
    void anEntityIsALowerCaseNameAndTheDefaultsMakeAWorkingConnection() {
        SettingsValidator.Result defaults = SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", KEY));

        assertThat(defaults.ok()).isTrue();
        assertThat(defaults.values()).containsEntry("format", "csv").containsEntry("inboxDirectory", "/inbox")
                .containsEntry("outboxDirectory", "/outbox").containsEntry("pollMinutes", "5")
                .doesNotContainKey("inboundEntity");
        for (String good : List.of("item", "work-order-2", "a")) {
            assertThat(SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", KEY, "inboundEntity", good)).ok())
                    .as(good).isTrue();
        }
        for (String bad : List.of("Item", "2items", "has space", "under_score", "x".repeat(41))) {
            assertThat(SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", KEY, "inboundEntity", bad))
                    .problems()).as(bad).containsKey("settings.inboundEntity");
        }
        assertThat(SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", KEY, "pollMinutes", "0")).problems())
                .containsKey("settings.pollMinutes");
        assertThat(SettingsValidator.validate(DESCRIPTION, Map.of("hostKey", KEY, "timeoutSeconds", "4")).problems())
                .containsKey("settings.timeoutSeconds");
    }

    // ---- the limits of picking files up ----

    @Test
    void theLimitsHaveSafeDefaultsAndRefuseWhatCouldNotWork() {
        IntegrationProperties.Files defaults = IntegrationProperties.Files.defaults();
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.maxFiles()).isEqualTo(50);
        assertThat(defaults.maxRows()).isEqualTo(10_000);
        assertThat(defaults.maxFileBytes()).isEqualTo(25 * 1024 * 1024);
        assertThat(defaults.minInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.ledgerRetention()).isEqualTo(Duration.ofDays(30));

        assertThatThrownBy(() -> new IntegrationProperties.Files(true, 0, Duration.ofMinutes(10),
                Duration.ofMinutes(5), Duration.ofMinutes(1), 50, 2000, 1024, 10, 2, Duration.ofDays(30)))
                .as("no polls at all").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties.Files(true, 4, Duration.ofMinutes(1),
                Duration.ofMinutes(5), Duration.ofMinutes(1), 50, 2000, 1024, 10, 2, Duration.ofDays(30)))
                .as("a deadline longer than the lease").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties.Files(true, 4, Duration.ofMinutes(10),
                Duration.ofMinutes(5), Duration.ofSeconds(1), 50, 2000, 1024, 10, 2, Duration.ofDays(30)))
                .as("looking more than every five seconds").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties.Files(true, 4, Duration.ofMinutes(10),
                Duration.ofMinutes(5), Duration.ofMinutes(1), 50, 10, 1024, 10, 2, Duration.ofDays(30)))
                .as("listing fewer names than files taken").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties.Files(true, 4, Duration.ofMinutes(10),
                Duration.ofMinutes(5), Duration.ofMinutes(1), 50, 2000, 200_000_000, 10, 2, Duration.ofDays(30)))
                .as("a file of two hundred megabytes").isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the sign-in ----

    private static Definition definition(String authKind, String credential) {
        return new Definition("Label", 1, "sftp://files.example.com", authKind, null, credential, Map.of(), List.of(),
                List.of(), "f".repeat(64));
    }

    private static CredentialLogins logins(TestSecrets.Store store) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (store != null) {
            beans.registerSingleton("store", store);
        }
        return new CredentialLogins(beans.getBeanProvider(SecretsStore.class));
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    @Test
    void aPasswordCredentialIsAUserAndAPasswordAndNothingMoreIsKept() {
        TenantId tenant = tenant();
        TestSecrets.Store store = new TestSecrets.Store();
        store.put(SecretRef.forTenant(tenant, "file-login"), SecretValue.of("user-a:pass:word-1"));

        Resolved resolved = logins(store).resolve(tenant, definition("BASIC", "file-login"));

        assertThat(resolved.failure()).isNull();
        Login login = resolved.login();
        assertThat(login.user()).isEqualTo("user-a");
        assertThat(new String(login.password(), StandardCharsets.UTF_8)).as("the first colon ends the user")
                .isEqualTo("pass:word-1");
        assertThat(login.privateKey()).isNull();
        assertThat(login.toString() + resolved).doesNotContain("pass").doesNotContain("user-a");
        login.destroy();
        assertThat(login.password()).containsOnly((byte) 0);
    }

    @Test
    void aKeyCredentialIsAUserOnTheFirstLineAndAKeyAfterIt() {
        TenantId tenant = tenant();
        TestSecrets.Store store = new TestSecrets.Store();
        String key = "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----";
        store.put(SecretRef.forTenant(tenant, "key-login"), SecretValue.of("user-a\n" + key + "\n"));

        Resolved resolved = logins(store).resolve(tenant, definition("SSH_KEY", "key-login"));

        assertThat(resolved.failure()).isNull();
        assertThat(resolved.login().user()).isEqualTo("user-a");
        assertThat(new String(resolved.login().privateKey(), StandardCharsets.UTF_8)).isEqualTo(key + "\n");
        assertThat(resolved.login().password()).isNull();
        resolved.login().destroy();
        assertThat(resolved.login().privateKey()).containsOnly((byte) 0);
    }

    @Test
    void aCredentialThatIsNotAUsableLoginIsSaidToBeUnusableAndNeverRepeated() {
        TenantId tenant = tenant();
        TestSecrets.Store store = new TestSecrets.Store();
        Map<String, String> unusable = Map.of(
                "no-colon", "just-a-password",
                "empty-user", ":password",
                "empty-password", "user-a:",
                "bad-user", "user a;rm:password",
                "long-user", "u".repeat(65) + ":password");
        unusable.forEach((name, value) -> store.put(SecretRef.forTenant(tenant, name), SecretValue.of(value)));
        store.put(SecretRef.forTenant(tenant, "no-line"), SecretValue.of("user-a"));
        store.put(SecretRef.forTenant(tenant, "no-key"), SecretValue.of("user-a\nnot a key"));
        store.put(SecretRef.forTenant(tenant, "huge-key"), SecretValue.of("user-a\n-----BEGIN PRIVATE KEY-----\n"
                + "A".repeat(17_000)));
        store.put(SecretRef.forTenant(tenant, "bad-key-user"), SecretValue.of("user a\n-----BEGIN PRIVATE KEY-----"));

        for (String name : unusable.keySet()) {
            assertThat(logins(store).resolve(tenant, definition("BASIC", name)).failure()).as(name)
                    .isEqualTo(TestReason.CREDENTIAL_UNUSABLE);
        }
        for (String name : List.of("no-line", "no-key", "huge-key", "bad-key-user")) {
            assertThat(logins(store).resolve(tenant, definition("SSH_KEY", name)).failure()).as(name)
                    .isEqualTo(TestReason.CREDENTIAL_UNUSABLE);
        }
    }

    @Test
    void aMissingCredentialABrokenStoreAndAWrongKindOfSignInAreStableReasons() {
        TenantId tenant = tenant();
        TestSecrets.Store store = new TestSecrets.Store();
        store.put(SecretRef.forTenant(tenant, "file-login"), SecretValue.of("user-a:pass"));

        assertThat(logins(store).resolve(tenant, definition("BASIC", "other")).failure())
                .isEqualTo(TestReason.CREDENTIAL_MISSING);
        assertThat(logins(store).resolve(tenant(), definition("BASIC", "file-login")).failure())
                .as("another organization's credential is not found").isEqualTo(TestReason.CREDENTIAL_MISSING);
        assertThat(logins(null).resolve(tenant, definition("BASIC", "file-login")).failure())
                .isEqualTo(TestReason.CREDENTIAL_STORE_UNAVAILABLE);
        store.breakDown(true);
        Resolved down = logins(store).resolve(tenant, definition("BASIC", "file-login"));
        assertThat(down.failure()).isEqualTo(TestReason.CREDENTIAL_STORE_UNAVAILABLE);
        assertThat(down.toString()).doesNotContain("down");
        store.breakDown(false);
        for (String kind : List.of("NONE", "BEARER_TOKEN", "API_KEY_HEADER")) {
            assertThat(logins(store).resolve(tenant, definition(kind, "file-login")).failure()).as(kind)
                    .isEqualTo(TestReason.CREDENTIAL_UNUSABLE);
        }
    }
}
