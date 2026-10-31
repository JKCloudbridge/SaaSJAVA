package app.platform.integration.adapter.soap;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.ConnectorDescription.EventSpec;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.OutboundRequest;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.adapter.ProbeRequest;
import app.platform.integration.adapter.SettingSpec;
import app.platform.integration.adapter.SettingSpec.Choice;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A web service that speaks SOAP 1.1 over HTTP (Sprint 29, ADR-0235). The adapter writes the envelope and reads the
 * answer; the framework makes the call through the one outbound client, so the address rules, the deadline, the size
 * caps and the credential handling are those of every other connector. The operations are named by a rule, not by a
 * service description: a create is {@code Create<Kind>}, a change {@code Update<Kind>}, a removal {@code Delete<Kind>},
 * each in the namespace the connection names, with the action {@code <prefix><operation>} and the identifier in the
 * element the connection names.
 *
 * <p>Out of scope in this release, on purpose: reading a service description (WSDL) to find operations, message
 * security (signing and encrypting the envelope), attachments, SOAP 1.2, and nested entities.
 */
@Component
public final class SoapConnector implements Connector {

    /** The key of the connector type. */
    public static final String KEY = "soap";

    private static final String NAMESPACE = "namespace";
    private static final String SERVICE_PATH = "servicePath";
    private static final String ACTION_PREFIX = "actionPrefix";
    private static final String ID_ELEMENT = "idElement";
    private static final String HEALTH_PATH = "healthPath";
    private static final String EXPECT = "expect";
    private static final String TIMEOUT = "timeoutSeconds";
    private static final String IDEMPOTENCY = "idempotencyHeader";
    private static final String SUCCESS = "success";
    private static final String EXACT = "exact";
    private static final String XML = "text/xml; charset=utf-8";

    private static final ConnectorDescription DESCRIPTION = new ConnectorDescription(KEY, "SOAP web service",
            "A web service that speaks SOAP 1.1 over HTTP. The platform asks one safe question to see that it is "
                    + "there, and sends records to it as envelopes whose operations are named by a fixed rule.",
            1,
            List.of(AuthKind.NONE, AuthKind.BEARER_TOKEN, AuthKind.API_KEY_HEADER, AuthKind.BASIC),
            List.of(
                    new SettingSpec(NAMESPACE, "Namespace", "The namespace of the service, for example "
                            + "urn:erp.example or a web address.", SettingSpec.Kind.URI, true, null, List.of(), null,
                            null),
                    SettingSpec.path(SERVICE_PATH, "Service path", "Where the service listens for envelopes.", "/"),
                    new SettingSpec(ACTION_PREFIX, "Action prefix", "Written before the operation in the action "
                            + "header. Leave empty if the service wants the operation only.", SettingSpec.Kind.URI,
                            false, null, List.of(), null, null),
                    new SettingSpec(ID_ELEMENT, "Identifier element", "The name of the element that carries the "
                            + "identifier of an entity.", SettingSpec.Kind.TEXT, false, "Id", List.of(), null, null),
                    SettingSpec.path(HEALTH_PATH, "Check path", "The path to ask to see that the service is "
                            + "there.", "/"),
                    SettingSpec.choice(EXPECT, "Counts as working", "Which answers show the service works.", SUCCESS,
                            List.of(new Choice(SUCCESS, "Any success answer (200 to 299)"),
                                    new Choice(EXACT, "Exactly 200"))),
                    SettingSpec.integer(TIMEOUT, "Seconds to wait", "How long to wait for an answer, 1 to 10.", 5, 1,
                            10),
                    new SettingSpec(IDEMPOTENCY, "Repeat-safety header", "The name of a header that makes the "
                            + "service ignore a repeated envelope. Leave empty if it has none.",
                            SettingSpec.Kind.TEXT, false, null, List.of(), null, null)),
            List.of(new EventSpec("record.created", "A record is created"),
                    new EventSpec("record.updated", "A record is changed"),
                    new EventSpec("record.deleted", "A record is deleted")));

    @Override
    public ConnectorDescription describe() {
        return DESCRIPTION;
    }

    @Override
    public ProbeRequest probe(Map<String, String> settings) {
        return new ProbeRequest(ProbeRequest.Method.GET, settings.get(HEALTH_PATH),
                Duration.ofSeconds(Long.parseLong(settings.get(TIMEOUT))));
    }

    @Override
    public boolean accepts(Map<String, String> settings, int status) {
        return EXACT.equals(settings.get(EXPECT)) ? status == 200 : status >= 200 && status < 300;
    }

    @Override
    public OutboundRequest send(Map<String, String> settings, Operation operation, CanonicalEntity entity) {
        String operationName = switch (operation) {
            case CREATE -> "Create";
            case UPDATE -> "Update";
            case DELETE -> "Delete";
        } + pascal(entity.kind());
        if (operation != Operation.CREATE && (entity.externalId() == null || entity.externalId().isEmpty())) {
            throw new PayloadException("The entity has no identifier.");
        }
        String prefix = settings.getOrDefault(ACTION_PREFIX, "");
        byte[] envelope = SoapXml.write(settings.get(NAMESPACE), operationName,
                settings.getOrDefault(ID_ELEMENT, "Id"), operation == Operation.DELETE
                        ? new CanonicalEntity(entity.kind(), entity.externalId(), Map.of()) : entity);
        return new OutboundRequest(OutboundRequest.Method.POST, settings.getOrDefault(SERVICE_PATH, "/"), XML,
                envelope, Map.of("SOAPAction", "\"" + prefix + operationName + "\""));
    }

    @Override
    public boolean accepted(Map<String, String> settings, Operation operation, int status) {
        return status >= 200 && status < 300;
    }

    @Override
    public CanonicalEntity receive(Map<String, String> settings, String kind, byte[] body) {
        return SoapXml.read(kind, settings.getOrDefault(ID_ELEMENT, "Id"), body);
    }

    @Override
    public String idempotencyHeader(Map<String, String> settings) {
        String name = settings.get(IDEMPOTENCY);
        return name == null || name.isBlank() ? null : name.strip();
    }

    /** {@code purchase-order} becomes {@code PurchaseOrder}; a name that is not plain stays unwritable. */
    static String pascal(String kind) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : kind.toCharArray()) {
            if (c == '-' || c == '_' || c == '.') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upper = false;
            }
        }
        return out.length() == 0 ? "Entity" : out.toString();
    }
}
