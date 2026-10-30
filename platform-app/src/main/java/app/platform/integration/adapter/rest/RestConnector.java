package app.platform.integration.adapter.rest;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.ConnectorDescription.EventSpec;
import app.platform.integration.adapter.ProbeRequest;
import app.platform.integration.adapter.SettingSpec;
import app.platform.integration.adapter.SettingSpec.Choice;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The sample connector of Sprint 26 (ADR-0207): any service that answers a plain request over HTTP. It proves the
 * framework end to end, and it is a real, usable type: a connection of this type shows whether the service is
 * reachable and accepts the credential. Sending and receiving records arrives with Sprint 28.
 */
@Component
public final class RestConnector implements Connector {

    /** The key of the connector type. */
    public static final String KEY = "rest";

    private static final String METHOD = "probeMethod";
    private static final String PATH = "healthPath";
    private static final String EXPECT = "expect";
    private static final String TIMEOUT = "timeoutSeconds";
    private static final String SUCCESS = "success";
    private static final String EXACT = "exact";

    private static final ConnectorDescription DESCRIPTION = new ConnectorDescription(KEY, "REST service",
            "A web service that answers a plain request. The platform asks one safe question to see that it is "
                    + "there and accepts the credential.",
            1,
            List.of(AuthKind.NONE, AuthKind.BEARER_TOKEN, AuthKind.API_KEY_HEADER, AuthKind.BASIC),
            List.of(
                    SettingSpec.path(PATH, "Check path", "The path to ask, for example /status.", "/"),
                    SettingSpec.choice(METHOD, "Check method", "GET asks for the page; HEAD asks only for its headers.",
                            "GET", List.of(new Choice("GET", "GET"), new Choice("HEAD", "HEAD"))),
                    SettingSpec.choice(EXPECT, "Counts as working", "Which answers show the service works.", SUCCESS,
                            List.of(new Choice(SUCCESS, "Any success answer (200 to 299)"),
                                    new Choice(EXACT, "Exactly 200"))),
                    SettingSpec.integer(TIMEOUT, "Seconds to wait", "How long to wait for an answer, 1 to 10.", 5, 1,
                            10)),
            List.of(new EventSpec("record.created", "A record is created"),
                    new EventSpec("record.updated", "A record is changed"),
                    new EventSpec("record.deleted", "A record is deleted")));

    @Override
    public ConnectorDescription describe() {
        return DESCRIPTION;
    }

    @Override
    public ProbeRequest probe(Map<String, String> settings) {
        return new ProbeRequest(ProbeRequest.Method.valueOf(settings.get(METHOD)), settings.get(PATH),
                Duration.ofSeconds(Long.parseLong(settings.get(TIMEOUT))));
    }

    @Override
    public boolean accepts(Map<String, String> settings, int status) {
        return EXACT.equals(settings.get(EXPECT)) ? status == 200 : status >= 200 && status < 300;
    }
}
