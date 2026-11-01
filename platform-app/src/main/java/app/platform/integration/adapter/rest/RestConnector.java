package app.platform.integration.adapter.rest;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.JobState;
import app.platform.integration.adapter.ConnectorDescription.EventSpec;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.OutboundRequest;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.adapter.ProbeRequest;
import app.platform.integration.adapter.SettingSpec;
import app.platform.integration.adapter.SettingSpec.Choice;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sample connector (ADR-0207): any service that answers a plain request over HTTP and speaks JSON. Since Sprint 26
 * a connection of this type shows whether the service is reachable and accepts the credential; since Sprint 28 it also
 * sends an entity as a JSON object (a create is a POST to {@code <entity path>/<kind>}, a change a PUT or PATCH and a
 * removal a DELETE to {@code <entity path>/<kind>/<identifier>}) and reads a JSON object back into an entity.
 */
@Component
public final class RestConnector implements Connector {

    /** The key of the connector type. */
    public static final String KEY = "rest";

    /** The setting that names the credential whose value signs the calls the service makes to the platform. */
    public static final String INBOUND_CREDENTIAL = "inboundCredential";

    private static final String METHOD = "probeMethod";
    private static final String PATH = "healthPath";
    private static final String EXPECT = "expect";
    private static final String TIMEOUT = "timeoutSeconds";
    private static final String ENTITY_PATH = "entityPath";
    private static final String ID_FIELD = "idField";
    private static final String UPDATE_METHOD = "updateMethod";
    private static final String SUCCESS = "success";
    private static final String EXACT = "exact";
    private static final String JSON = "application/json";
    private static final String STATUS_PATH = "statusPath";

    private static final ConnectorDescription DESCRIPTION = new ConnectorDescription(KEY, "REST service",
            "A web service that answers a plain request and speaks JSON. The platform asks one safe question to see "
                    + "that it is there and accepts the credential, sends records to it and reads signed changes "
                    + "from it.",
            2,
            List.of(AuthKind.NONE, AuthKind.BEARER_TOKEN, AuthKind.API_KEY_HEADER, AuthKind.BASIC),
            List.of(
                    SettingSpec.path(PATH, "Check path", "The path to ask, for example /status.", "/"),
                    SettingSpec.choice(METHOD, "Check method", "GET asks for the page; HEAD asks only for its headers.",
                            "GET", List.of(new Choice("GET", "GET"), new Choice("HEAD", "HEAD"))),
                    SettingSpec.choice(EXPECT, "Counts as working", "Which answers show the service works.", SUCCESS,
                            List.of(new Choice(SUCCESS, "Any success answer (200 to 299)"),
                                    new Choice(EXACT, "Exactly 200"))),
                    SettingSpec.integer(TIMEOUT, "Seconds to wait", "How long to wait for an answer, 1 to 10.", 5, 1,
                            10),
                    SettingSpec.path(ENTITY_PATH, "Records path", "Where the entities live; each kind is a path "
                            + "below it, for example /api.", "/"),
                    text(ID_FIELD, "Identifier field", "The name of the field that carries the identifier of an "
                            + "entity.", "id"),
                    SettingSpec.choice(UPDATE_METHOD, "Method for a change", "How a changed entity is sent.", "PUT",
                            List.of(new Choice("PUT", "PUT"), new Choice("PATCH", "PATCH"))),
                    text(INBOUND_CREDENTIAL, "Signing credential for changes coming in", "The name of the credential "
                            + "whose value the service uses to sign the changes it sends. Leave empty if it sends "
                            + "none.", null)),
            List.of(new EventSpec("record.created", "A record is created"),
                    new EventSpec("record.updated", "A record is changed"),
                    new EventSpec("record.deleted", "A record is deleted")));

    private final JsonMapper json = JsonMapper.builder().build();

    private static SettingSpec text(String key, String label, String hint, String defaultValue) {
        return new SettingSpec(key, label, hint, SettingSpec.Kind.TEXT, false, defaultValue, List.of(), null, null);
    }

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

    @Override
    public OutboundRequest send(Map<String, String> settings, Operation operation, CanonicalEntity entity) {
        String base = entityBase(settings) + "/" + entity.kind();
        return switch (operation) {
            case CREATE -> new OutboundRequest(OutboundRequest.Method.POST, base, JSON, body(settings, entity));
            case UPDATE -> new OutboundRequest(
                    OutboundRequest.Method.valueOf(settings.getOrDefault(UPDATE_METHOD, "PUT")),
                    base + "/" + identifier(entity), JSON, body(settings, entity));
            case DELETE -> new OutboundRequest(OutboundRequest.Method.DELETE, base + "/" + identifier(entity), null,
                    new byte[0]);
        };
    }

    @Override
    public boolean accepted(Map<String, String> settings, Operation operation, int status) {
        return status >= 200 && status < 300 || operation == Operation.DELETE && status == 404;
    }

    @Override
    public CanonicalEntity receive(Map<String, String> settings, String kind, byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException unreadable) {
            throw new PayloadException("The payload is not JSON.");
        }
        if (root == null || !root.isObject()) {
            throw new PayloadException("The payload is not a JSON object.");
        }
        String idField = settings.getOrDefault(ID_FIELD, "id");
        String id = null;
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            Object read = scalar(entry.getValue());
            if (read == null) {
                continue;
            }
            if (entry.getKey().equals(idField)) {
                id = read.toString();
                continue;
            }
            fields.put(entry.getKey(), read);
        }
        return new CanonicalEntity(kind, id, fields);
    }

    @Override
    public String idempotencyHeader(Map<String, String> settings) {
        return "Idempotency-Key";
    }

    /**
     * A service that answers {@code 202} with {@code {"statusPath": "/some/path"}} has accepted the work and says where
     * to ask about it (ADR-0233). Any other answer is final at once.
     */
    @Override
    public Optional<String> remoteJob(Map<String, String> settings, Operation operation, int status, byte[] body) {
        if (status != 202) {
            return Optional.empty();
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode path = root == null ? null : root.get(STATUS_PATH);
            return path != null && path.isString() ? Optional.of(path.asString()) : Optional.empty();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    @Override
    public ProbeRequest jobRequest(Map<String, String> settings, String jobPath) {
        return new ProbeRequest(ProbeRequest.Method.GET, jobPath,
                Duration.ofSeconds(Long.parseLong(settings.get(TIMEOUT))));
    }

    /** {@code {"state": "running" | "done" | "failed"}}; anything else counts as failed. */
    @Override
    public JobState jobState(Map<String, String> settings, int status, byte[] body) {
        if (status < 200 || status >= 300) {
            return JobState.FAILED;
        }
        try {
            JsonNode state = json.readTree(body).get("state");
            String text = state != null && state.isString() ? state.asString() : "";
            return switch (text) {
                case "running", "pending", "queued" -> JobState.RUNNING;
                case "done", "completed" -> JobState.DONE;
                default -> JobState.FAILED;
            };
        } catch (RuntimeException unreadable) {
            return JobState.FAILED;
        }
    }

    /** A JSON scalar as text, a number or a yes and no; anything nested or null is not read. */
    private static Object scalar(JsonNode value) {
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return new BigDecimal(value.asString());
        }
        return null;
    }

    private byte[] body(Map<String, String> settings, CanonicalEntity entity) {
        ObjectNode node = json.createObjectNode();
        String idField = settings.getOrDefault(ID_FIELD, "id");
        if (entity.externalId() != null) {
            node.put(idField, entity.externalId());
        }
        for (Map.Entry<String, Object> entry : entity.fields().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean bool) {
                node.put(entry.getKey(), bool.booleanValue());
            } else if (value instanceof BigDecimal number) {
                node.put(entry.getKey(), number);
            } else {
                node.put(entry.getKey(), value.toString());
            }
        }
        return json.writeValueAsBytes(node);
    }

    private static String identifier(CanonicalEntity entity) {
        if (entity.externalId() == null || entity.externalId().isEmpty()) {
            throw new PayloadException("The entity has no identifier.");
        }
        return percentEncoded(entity.externalId());
    }

    /** Everything but letters, digits and the four unreserved marks, as percent-escapes of its UTF-8 bytes. */
    private static String percentEncoded(String text) {
        StringBuilder out = new StringBuilder();
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            int c = value & 0xff;
            boolean plain = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-'
                    || c == '.' || c == '_' || c == '~';
            if (plain) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }

    private static String entityBase(Map<String, String> settings) {
        String path = settings.getOrDefault(ENTITY_PATH, "/");
        return "/".equals(path) ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
