package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.ConnectorDescription.EventSpec;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.OutboundRequest;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.adapter.ProbeRequest;
import app.platform.integration.adapter.rest.RestConnector;
import app.platform.integration.internal.ConnectionStore.Definition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import app.platformapi.ConnectorTypeView;
import app.platformapi.IntegrationView;
import app.platformapi.SaveIntegrationRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The catalogue of connector types, the sample connector, the checks of a definition and the settings. */
class ConnectorFrameworkTest {

    private static final EndpointRules.Policy NO_DEV_HOSTS = new EndpointRules.Policy(Set.of());

    private static Connector connector(String key, String... events) {
        ConnectorDescription description = new ConnectorDescription(key, "Label", "Text.", 1,
                List.of(AuthKind.NONE), List.of(),
                java.util.Arrays.stream(events).map(event -> new EventSpec(event, event)).toList());
        return new Connector() {
            @Override
            public ConnectorDescription describe() {
                return description;
            }

            @Override
            public ProbeRequest probe(Map<String, String> settings) {
                return new ProbeRequest(ProbeRequest.Method.GET, "/", Duration.ofSeconds(1));
            }

            @Override
            public boolean accepts(Map<String, String> settings, int status) {
                return status == 200;
            }

            @Override
            public OutboundRequest send(Map<String, String> settings, Operation operation, CanonicalEntity entity) {
                throw new UnsupportedOperationException("A test connector sends nothing");
            }

            @Override
            public boolean accepted(Map<String, String> settings, Operation operation, int status) {
                return status == 200;
            }

            @Override
            public CanonicalEntity receive(Map<String, String> settings, String kind, byte[] body) {
                throw new PayloadException("A test connector reads nothing");
            }
        };
    }

    @Test
    void theCatalogueFindsEveryConnectorByKeyAndDescribesItForTheScreens() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(new RestConnector(),
                connector("another-one", "record.created")));

        assertThat(registry.find("rest")).isPresent();
        assertThat(registry.find("nothing")).isEmpty();
        List<ConnectorTypeView> views = registry.views();
        assertThat(views).extracting(ConnectorTypeView::key).containsExactly("another-one", "rest");
        ConnectorTypeView rest = registry.view("rest").orElseThrow();
        assertThat(rest.authKinds()).extracting(ConnectorTypeView.ConnectorChoice::key)
                .containsExactly("NONE", "BEARER_TOKEN", "API_KEY_HEADER", "BASIC");
        assertThat(rest.authKinds()).extracting(ConnectorTypeView.ConnectorChoice::label)
                .allMatch(label -> !label.isBlank());
        assertThat(rest.settings()).extracting(ConnectorTypeView.Setting::key)
                .containsExactly("healthPath", "probeMethod", "expect", "timeoutSeconds", "entityPath", "idField",
                        "updateMethod", "inboundCredential");
        assertThat(rest.settings().get(3).min()).isEqualTo(1L);
        assertThat(rest.settings().get(3).max()).isEqualTo(10L);
        assertThat(rest.platformEvents()).extracting(ConnectorTypeView.ConnectorChoice::key)
                .containsExactly("record.created", "record.updated", "record.deleted");
    }

    @Test
    void twoConnectorsWithTheSameKeyOrAnEventThePlatformDoesNotOfferStopTheStart() {
        assertThatThrownBy(() -> new ConnectorRegistry(List.of(new RestConnector(), new RestConnector())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rest");
        assertThatThrownBy(() -> new ConnectorRegistry(List.of(connector("odd", "record.exploded"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("odd");
    }

    @Test
    void theSampleConnectorAsksOneSafeQuestionAndJudgesTheAnswerBySetting() {
        RestConnector rest = new RestConnector();
        Map<String, String> defaults = SettingsValidator.validate(rest.describe(), Map.of()).values();
        Map<String, String> exact = SettingsValidator.validate(rest.describe(),
                Map.of("expect", "exact", "probeMethod", "HEAD", "healthPath", "/up", "timeoutSeconds", "2")).values();

        ProbeRequest asked = rest.probe(exact);
        assertThat(asked.method()).isEqualTo(ProbeRequest.Method.HEAD);
        assertThat(asked.path()).isEqualTo("/up");
        assertThat(asked.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(rest.probe(defaults).method()).isEqualTo(ProbeRequest.Method.GET);
        assertThat(rest.accepts(defaults, 200)).isTrue();
        assertThat(rest.accepts(defaults, 204)).isTrue();
        assertThat(rest.accepts(defaults, 404)).isFalse();
        assertThat(rest.accepts(defaults, 500)).isFalse();
        assertThat(rest.accepts(exact, 204)).isFalse();
        assertThat(rest.accepts(exact, 200)).isTrue();
    }

    private static DefinitionChecks checks(String... objects) {
        Metadata metadata = mock(Metadata.class);
        when(metadata.object(anyString())).thenReturn(Optional.empty());
        for (String object : objects) {
            when(metadata.object(object)).thenReturn(Optional.of(mock(ObjectDefinition.class)));
        }
        return new DefinitionChecks(new ConnectorRegistry(List.of(new RestConnector())), metadata, NO_DEV_HOSTS);
    }

    private static SaveIntegrationRequest request(String url, String auth, String header, String credential,
            List<IntegrationView.Subscription> subscriptions, List<IntegrationView.ScopeEntry> scope) {
        return new SaveIntegrationRequest("erp-link", " ERP link ", "rest", url, auth, header, credential, Map.of(),
                subscriptions, scope, 0L);
    }

    @Test
    void aGoodDefinitionBecomesOneWrittenOnlyOneWayWithItsFingerprint() {
        DefinitionChecks checks = checks("Order__c");

        Definition definition = checks.check(request(" HTTPS://Api.Example.com:443/v1/ ", "BEARER_TOKEN", null,
                "erp-token", List.of(new IntegrationView.Subscription("record.created", "Order__c")),
                List.of(new IntegrationView.ScopeEntry("Order__c", true, false, true))), new RestConnector());

        assertThat(definition.label()).isEqualTo("ERP link");
        assertThat(definition.baseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(definition.settings()).containsEntry("healthPath", "/").containsEntry("timeoutSeconds", "5");
        assertThat(definition.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(definition.authHeaderName()).isNull();
    }

    @Test
    void everyProblemIsCollectedAtItsPlaceAndNoneRepeatsWhatWasTyped() {
        DefinitionChecks checks = checks("Order__c");

        assertThatThrownBy(() -> checks.check(request("http://127.0.0.1/secret-path", "MAGIC", "X-Typed", "UPPER",
                List.of(new IntegrationView.Subscription("record.exploded", "Order__c"),
                        new IntegrationView.Subscription("record.created", "Unknown__c")),
                List.of(new IntegrationView.ScopeEntry("Order__c", false, false, false))), new RestConnector()))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.fields()).containsKeys("baseUrl", "authKind", "subscriptions", "scope");
                    assertThat(failure.fields().toString()).doesNotContain("127.0.0.1").doesNotContain("secret-path")
                            .doesNotContain("MAGIC").doesNotContain("Unknown__c").doesNotContain("record.exploded");
                });
    }

    @Test
    void theCredentialAndTheHeaderFollowTheWayOfSigningIn() {
        DefinitionChecks checks = checks();
        RestConnector rest = new RestConnector();

        assertThat(problems(checks, rest, request("https://a.example.com", "NONE", null, "name-1", List.of(),
                List.of()))).containsKey("credentialName");
        assertThat(problems(checks, rest, request("https://a.example.com", "BASIC", null, null, List.of(),
                List.of()))).containsKey("credentialName");
        assertThat(problems(checks, rest, request("https://a.example.com", "BASIC", null, "Not Valid", List.of(),
                List.of()))).containsKey("credentialName");
        assertThat(problems(checks, rest, request("https://a.example.com", "API_KEY_HEADER", null, "key-1",
                List.of(), List.of()))).containsKey("authHeaderName");
        assertThat(problems(checks, rest, request("https://a.example.com", "API_KEY_HEADER", "Authorization",
                "key-1", List.of(), List.of()))).as("a header the platform sets itself").containsKey("authHeaderName");
        assertThat(problems(checks, rest, request("https://a.example.com", "API_KEY_HEADER", "X-Api-Key", "key-1",
                List.of(), List.of()))).isEmpty();
        assertThat(problems(checks, rest, request("https://a.example.com", "BEARER_TOKEN", "X-Api-Key", "key-1",
                List.of(), List.of()))).containsKey("authHeaderName");
    }

    private static Map<String, List<String>> problems(DefinitionChecks checks, Connector connector,
            SaveIntegrationRequest request) {
        try {
            checks.check(request, connector);
            return Map.of();
        } catch (ApiException failure) {
            return failure.fields();
        }
    }

    @Test
    void aDevelopmentHostIsRefusedToStartInTheProductionProfileAndOnlyThere() {
        IntegrationProperties withHost = new IntegrationProperties(new IntegrationProperties.Limits(20),
                new IntegrationProperties.Egress(Duration.ofSeconds(3), Duration.ofSeconds(5), 16384,
                        List.of(" 127.0.0.1 ", "LOCALHOST")),
                new IntegrationProperties.Testing(Duration.ofSeconds(10), Duration.ofSeconds(5), 4),
                new IntegrationProperties.Flow(16, Duration.ofSeconds(10), 262144, 65536, 262144,
                        Duration.ofMinutes(5), 120, Duration.ofDays(30), Duration.ofMinutes(10)));
        IntegrationModuleConfiguration configuration = new IntegrationModuleConfiguration();
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");

        assertThatThrownBy(() -> configuration.endpointPolicy(withHost, production))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
        EndpointRules.Policy policy = configuration.endpointPolicy(withHost, test);
        assertThat(policy.isDevelopmentHost("127.0.0.1")).isTrue();
        assertThat(policy.isDevelopmentHost("localhost")).isTrue();
        assertThat(policy.isDevelopmentHost("example.com")).isFalse();
    }

    @Test
    void theBoundsOfTheSettingsAreChecked() {
        assertThatThrownBy(() -> new IntegrationProperties.Limits(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties.Egress(Duration.ofMillis(5), Duration.ofSeconds(5), 16384,
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties.Egress(Duration.ofSeconds(3), Duration.ofSeconds(5), 10,
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationProperties.Testing(Duration.ofSeconds(100), Duration.ofSeconds(5), 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
