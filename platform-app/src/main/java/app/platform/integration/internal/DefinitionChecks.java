package app.platform.integration.internal;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.internal.ConnectionStore.Definition;
import app.platform.integration.internal.EndpointRules.Endpoint;
import app.platform.metadata.Metadata;
import app.platformapi.ApiException;
import app.platformapi.IntegrationView;
import app.platformapi.SaveIntegrationRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns what a person typed into a definition the platform keeps, or says in fixed sentences, by place, what is wrong
 * (ADR-0208). Every problem is collected, none repeats what was typed, and nothing is executable: the definition is
 * a closed set of keys, names and numbers checked against the connector type, the object catalogue and the address
 * rules.
 */
@Component
class DefinitionChecks {

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,39}");
    private static final Pattern CREDENTIAL = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");
    private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length", "content-type",
            "transfer-encoding", "connection", "upgrade", "te", "trailer", "expect", "user-agent", "accept",
            "cookie", "set-cookie", "authorization", "proxy-authorization", "forwarded", "x-forwarded-for",
            "x-forwarded-host", "x-real-ip", "date", "range", "if-match", "if-none-match");

    private final ConnectorRegistry registry;
    private final Metadata metadata;
    private final EndpointRules.Policy policy;

    DefinitionChecks(ConnectorRegistry registry, Metadata metadata, EndpointRules.Policy policy) {
        this.registry = registry;
        this.metadata = metadata;
        this.policy = policy;
    }

    /** The definition for the request, for a connector type that exists, or a validation error with every problem. */
    Definition check(SaveIntegrationRequest request, Connector connector) {
        ConnectorDescription description = connector.describe();
        Map<String, List<String>> problems = new LinkedHashMap<>();

        String label = request.label().strip();
        if (label.isEmpty() || label.length() > 80 || label.codePoints().anyMatch(DefinitionChecks::unsafeText)) {
            problems.put("label", List.of("Use a name of 1 to 80 characters without markup or control characters."));
        }

        String baseUrl = request.baseUrl();
        EndpointRules.Result address = EndpointRules.parse(baseUrl.strip(), policy);
        if (address.ok()) {
            baseUrl = canonical(address.endpoint());
        } else {
            problems.put("baseUrl", List.of(address.refusal().words()));
        }

        AuthKind auth = AuthKind.fromKey(request.authKind()).filter(description.authKinds()::contains)
                .orElse(null);
        String header = blankToNull(request.authHeaderName());
        String credential = blankToNull(request.credentialName());
        if (auth == null) {
            problems.put("authKind", List.of("Choose one of the ways this kind of connection can sign in."));
        } else {
            authProblems(auth, header, credential, problems);
        }

        SettingsValidator.Result settings = SettingsValidator.validate(description, request.settings());
        problems.putAll(settings.problems());
        List<IntegrationView.Subscription> subscriptions = subscriptions(request, description, problems);
        List<IntegrationView.ScopeEntry> scope = scope(request, problems);

        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        String fingerprint = Fingerprints.of(description.key(), description.version(), baseUrl, auth.name(), header,
                credential, settings.values());
        return new Definition(label, description.version(), baseUrl, auth.name(), header, credential,
                settings.values(), subscriptions, scope, fingerprint);
    }

    private static void authProblems(AuthKind auth, String header, String credential,
            Map<String, List<String>> problems) {
        if (auth == AuthKind.NONE) {
            if (credential != null) {
                problems.put("credentialName", List.of("This connection sends no credential."));
            }
        } else if (credential == null || !CREDENTIAL.matcher(credential).matches()) {
            problems.put("credentialName", List.of("Name the credential to use: lower case letters, digits, dots, "
                    + "dashes and underscores."));
        }
        if (auth == AuthKind.API_KEY_HEADER) {
            if (header == null || !HEADER.matcher(header).matches()
                    || RESERVED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                problems.put("authHeaderName", List.of("Use a header name of letters, digits and dashes that the "
                        + "platform does not set itself."));
            }
        } else if (header != null) {
            problems.put("authHeaderName", List.of("Only a connection that sends the credential in a header "
                    + "names one."));
        }
    }

    private List<IntegrationView.Subscription> subscriptions(SaveIntegrationRequest request,
            ConnectorDescription description, Map<String, List<String>> problems) {
        Set<String> events = new HashSet<>();
        description.platformEvents().forEach(event -> events.add(event.key()));
        List<IntegrationView.Subscription> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean bad = false;
        for (IntegrationView.Subscription subscription : request.subscriptions()) {
            if (!events.contains(subscription.event()) || metadata.object(subscription.object()).isEmpty()
                    || !seen.add(subscription.event() + "|" + subscription.object())) {
                bad = true;
            } else {
                result.add(subscription);
            }
        }
        if (bad) {
            problems.put("subscriptions", List.of("Each event must be one this kind of connection offers, for an "
                    + "object of the organization, and listed once."));
        }
        return result;
    }

    private List<IntegrationView.ScopeEntry> scope(SaveIntegrationRequest request,
            Map<String, List<String>> problems) {
        List<IntegrationView.ScopeEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean bad = false;
        for (IntegrationView.ScopeEntry entry : request.scope()) {
            boolean anything = Boolean.TRUE.equals(entry.read()) || Boolean.TRUE.equals(entry.create())
                    || Boolean.TRUE.equals(entry.update());
            if (!anything || metadata.object(entry.object()).isEmpty() || !seen.add(entry.object())) {
                bad = true;
            } else {
                result.add(entry);
            }
        }
        if (bad) {
            problems.put("scope", List.of("Each entry must be an object of the organization, listed once, with at "
                    + "least one thing the connection may do."));
        }
        return result;
    }

    /** The address written one way, so the same address always gives the same fingerprint. */
    static String canonical(Endpoint endpoint) {
        return (endpoint.secure() ? "https://" : "http://") + endpoint.hostHeader() + endpoint.basePath();
    }

    private static boolean unsafeText(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.UNASSIGNED || codePoint == '<' || codePoint == '>';
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
