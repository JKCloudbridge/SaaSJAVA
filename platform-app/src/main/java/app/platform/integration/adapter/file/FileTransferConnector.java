package app.platform.integration.adapter.file;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.ConnectorDescription.EventSpec;
import app.platform.integration.adapter.FileFlowConnector;
import app.platform.integration.adapter.FileFormat;
import app.platform.integration.adapter.FileLayout;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.OutboundRequest;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.adapter.ProbeRequest;
import app.platform.integration.adapter.SettingSpec;
import app.platform.integration.adapter.SettingSpec.Choice;
import app.platform.integration.adapter.SettingSpec.Kind;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * File transfer (Sprint 29A, ADR-0237, ADR-0240): a server that exchanges files over a secure shell session. The
 * platform writes one file for each change into an outbox directory (written under a temporary name and renamed, so a
 * reader never meets half a file) and picks up the files that arrive in an inbox directory, one at a time, moving each
 * through a processing directory to a done or an error directory. The format is comma separated values or one JSON
 * object per line, both read by strict code of the platform.
 *
 * <p>The address is {@code sftp://host[:port]}. The server's public key is pinned in the settings (a fingerprint typed
 * by the administrator); the platform never trusts a key on first sight. The sign-in is a named credential: a user
 * name and password, or a user name and private key.
 */
@Component
public final class FileTransferConnector implements FileFlowConnector {

    /** The key of the connector type. */
    public static final String KEY = "file-transfer";

    private static final String HOST_KEY = "hostKey";
    private static final String FORMAT = "format";
    private static final String OUTBOX = "outboxDirectory";
    private static final String INBOX = "inboxDirectory";
    private static final String PROCESSING = "processingDirectory";
    private static final String DONE = "doneDirectory";
    private static final String ERROR = "errorDirectory";
    private static final String INBOUND_ENTITY = "inboundEntity";
    private static final String ID_COLUMN = "idColumn";
    private static final String OPERATION_COLUMN = "operationColumn";
    private static final String POLL_MINUTES = "pollMinutes";
    private static final String TIMEOUT = "timeoutSeconds";

    private static final ConnectorDescription DESCRIPTION = new ConnectorDescription(KEY, "File transfer (SFTP)",
            "A server that exchanges files over a secure shell session. The platform writes a file for each change "
                    + "and picks up the files that arrive, checking the server's key every time.",
            1,
            List.of(AuthKind.BASIC, AuthKind.SSH_KEY),
            List.of(
                    SettingSpec.of(HOST_KEY, "Server key fingerprint", "The fingerprint of the server's public key, "
                            + "for example SHA256:... as the usual tools print it. The platform refuses a server "
                            + "that shows another key.", Kind.FINGERPRINT, true),
                    SettingSpec.choice(FORMAT, "File format", "Comma separated values with a header line, or one "
                            + "JSON object per line. Used for files in both directions.", "csv",
                            List.of(new Choice("csv", "Comma separated values"),
                                    new Choice("jsonl", "One JSON object per line"))),
                    SettingSpec.directory(OUTBOX, "Outbox directory", "Where the platform writes files for the "
                            + "other side.", "/outbox"),
                    SettingSpec.directory(INBOX, "Inbox directory", "Where files from the other side wait to be "
                            + "picked up.", "/inbox"),
                    SettingSpec.directory(PROCESSING, "Processing directory", "Where a picked-up file is moved "
                            + "while it is applied.", "/processing"),
                    SettingSpec.directory(DONE, "Done directory", "Where a file that was applied completely is "
                            + "moved. The platform never deletes anything on the server.", "/done"),
                    SettingSpec.directory(ERROR, "Error directory", "Where a file that was set aside is moved, for "
                            + "a person to look at.", "/error"),
                    SettingSpec.of(INBOUND_ENTITY, "Entity in incoming files", "The kind of entity the files in the "
                            + "inbox hold, as a mapping of this connection names it. Leave empty to pick up no "
                            + "files.", Kind.IDENTIFIER, false),
                    new SettingSpec(ID_COLUMN, "Identifier column", "The column or key that holds the identifier of "
                            + "each entity.", Kind.TEXT, false, "id", List.of(), null, null),
                    new SettingSpec(OPERATION_COLUMN, "Operation column", "The column or key in which a written "
                            + "file says whether the entity was created, changed or deleted.", Kind.TEXT, false,
                            "operation", List.of(), null, null),
                    SettingSpec.integer(POLL_MINUTES, "Minutes between looks", "How often the inbox is looked at, "
                            + "1 to 1440.", 5, 1, 1440),
                    SettingSpec.integer(TIMEOUT, "Seconds to wait", "How long one visit to the server may take, 5 "
                            + "to 120.", 30, 5, 120)),
            List.of(new EventSpec("record.created", "A record is created"),
                    new EventSpec("record.updated", "A record is changed"),
                    new EventSpec("record.deleted", "A record is deleted")));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public ConnectorDescription describe() {
        return DESCRIPTION;
    }

    @Override
    public FileLayout layout(Map<String, String> settings) {
        FileFormat format = FileFormat.fromKey(settings.get(FORMAT)).orElse(FileFormat.CSV);
        String entity = settings.get(INBOUND_ENTITY);
        return new FileLayout(settings.get(INBOX), settings.get(PROCESSING), settings.get(DONE), settings.get(ERROR),
                settings.get(OUTBOX), format, entity == null || entity.isBlank() ? null : entity,
                settings.get(ID_COLUMN), settings.get(OPERATION_COLUMN),
                Duration.ofMinutes(Long.parseLong(settings.get(POLL_MINUTES))),
                Duration.ofSeconds(Long.parseLong(settings.get(TIMEOUT))));
    }

    @Override
    public Map<String, String> settingProblems(Map<String, String> settings) {
        Map<String, String> problems = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String key : List.of(OUTBOX, INBOX, PROCESSING, DONE, ERROR)) {
            String value = settings.get(key);
            if (value != null && !seen.add(value)) {
                problems.put("settings." + key, "Each directory must be a different one.");
            }
        }
        String id = settings.get(ID_COLUMN);
        String operation = settings.get(OPERATION_COLUMN);
        if (id != null && !CsvFiles.COLUMN.matcher(id).matches()) {
            problems.put("settings." + ID_COLUMN, "Use a name of letters, digits, dots, dashes and underscores.");
        }
        if (operation != null && !CsvFiles.COLUMN.matcher(operation).matches()) {
            problems.put("settings." + OPERATION_COLUMN,
                    "Use a name of letters, digits, dots, dashes and underscores.");
        }
        if (id != null && id.equals(operation)) {
            problems.put("settings." + OPERATION_COLUMN, "The identifier and the operation need different names.");
        }
        return problems;
    }

    /** Not used: a connection of this kind is tested by the transport, which visits the server. */
    @Override
    public ProbeRequest probe(Map<String, String> settings) {
        return new ProbeRequest(ProbeRequest.Method.GET, "/", Duration.ofSeconds(5));
    }

    /** Not used: see {@link #probe}. */
    @Override
    public boolean accepts(Map<String, String> settings, int status) {
        return false;
    }

    @Override
    public OutboundRequest send(Map<String, String> settings, Operation operation, CanonicalEntity entity) {
        FileLayout layout = layout(settings);
        byte[] content = switch (layout.format()) {
            case CSV -> CsvFiles.write(layout.idColumn(), layout.operationColumn(), operation, entity);
            case JSONL -> JsonLinesFiles.write(layout.idColumn(), layout.operationColumn(), operation, entity);
        };
        // The name is the framework's: it is made from the delivery, never from a value. This is only its ending.
        return new OutboundRequest(OutboundRequest.Method.PUT, "/file" + layout.format().extension(),
                layout.format().mediaType(), content);
    }

    @Override
    public boolean accepted(Map<String, String> settings, Operation operation, int status) {
        return status >= 200 && status < 300;
    }

    /** Reads the small answer the transport gives after a file was written: {@code {"id": "..."}}. */
    @Override
    public CanonicalEntity receive(Map<String, String> settings, String kind, byte[] body) {
        try {
            JsonNode id = JSON.readTree(body).get("id");
            if (id == null || !id.isString()) {
                throw new PayloadException("The answer holds no identifier.");
            }
            return new CanonicalEntity(kind, id.asString(), Map.of());
        } catch (PayloadException refused) {
            throw refused;
        } catch (RuntimeException unreadable) {
            throw new PayloadException("The answer is not JSON.");
        }
    }

    @Override
    public List<CanonicalEntity> read(Map<String, String> settings, byte[] content, int maxRows) {
        FileLayout layout = layout(settings);
        return switch (layout.format()) {
            case CSV -> CsvFiles.read(content, layout.inboundEntity(), layout.idColumn(), layout.operationColumn(),
                    maxRows);
            case JSONL -> JsonLinesFiles.read(content, layout.inboundEntity(), layout.idColumn(),
                    layout.operationColumn(), maxRows);
        };
    }
}
