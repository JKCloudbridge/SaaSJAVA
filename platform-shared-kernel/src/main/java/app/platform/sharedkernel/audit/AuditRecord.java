package app.platform.sharedkernel.audit;

import app.platform.sharedkernel.TenantId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit record: what happened, to whom, in which tenant, with what result and why.
 *
 * <p>The record carries identifiers and facts, never secrets. The attribute keys are checked: a key that contains a
 * word for secret material (for example {@code password}, {@code token}, {@code secret}) is refused when the record is
 * built, so a mistake fails in a test instead of leaking into the audit trail. Values are cut to a bounded length.
 * The {@code reason} is the true internal reason of a failure, which the caller of the API never sees (ADR-0005).
 *
 * @param type what happened: lower-case words separated by dots, for example {@code auth.sign_in.failed}
 * @param outcome how it ended
 * @param actorUserId the user the record is about or who acted, if known
 * @param tenantId the tenant of the request, or null on the platform host
 * @param reason the internal reason code of a failure or refusal, or null
 * @param attributes further facts (keys are lower-case words with underscores)
 * @param objectKey the object (kind of record) the change was about, or null (the data engine fills it from
 *        Milestone 3 on)
 * @param recordId the record the change was about, or null
 * @param oldValue what it was before, as a short value or key (cut to {@link #MAX_CHANGE_LENGTH}), or null
 * @param newValue what it is now, likewise, or null
 * @param source where the record came from, or null to let the recorder decide (a request: API, otherwise SYSTEM)
 */
public record AuditRecord(String type, AuditOutcome outcome, UUID actorUserId, TenantId tenantId, String reason,
        Map<String, String> attributes, String objectKey, String recordId, String oldValue, String newValue,
        AuditSource source) {

    /** Largest stored attribute value. */
    public static final int MAX_VALUE_LENGTH = 200;

    /** Longest stored old or new value. */
    public static final int MAX_CHANGE_LENGTH = 500;

    /** Most attributes of one record. */
    public static final int MAX_ATTRIBUTES = 20;

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Pattern OBJECT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,119}");
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,59}");
    private static final Set<String> SECRET_WORDS = Set.of(
            "password", "passwd", "secret", "token", "credential", "authorization", "cookie", "verifier", "code");

    public AuditRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcome, "outcome");
        if (!TYPE.matcher(type).matches() || type.length() > 100) {
            throw new IllegalArgumentException("An audit type is lower-case words separated by dots");
        }
        if (reason != null && !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("An audit reason is a lower-case code with underscores");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (attributes != null) {
            if (attributes.size() > MAX_ATTRIBUTES) {
                throw new IllegalArgumentException("An audit record has at most " + MAX_ATTRIBUTES + " attributes");
            }
            attributes.forEach((key, value) -> {
                if (!KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("An audit attribute key is a lower-case word with underscores");
                }
                for (String word : key.split("_")) {
                    if (SECRET_WORDS.contains(word)) {
                        throw new IllegalArgumentException("An audit attribute must not hold secret material");
                    }
                }
                String text = value == null ? "" : value;
                copy.put(key, text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH) : text);
            });
        }
        attributes = Map.copyOf(copy);
        if (objectKey != null && !OBJECT_KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("An audit object key is an API name, or an object and a field name");
        }
        if (recordId != null && !RECORD_ID.matcher(recordId).matches()) {
            throw new IllegalArgumentException("An audit record id is a short identifier");
        }
        oldValue = cut(oldValue);
        newValue = cut(newValue);
    }

    /** A record without the fields of audit v1 (what every caller of audit v0 builds). */
    public AuditRecord(String type, AuditOutcome outcome, UUID actorUserId, TenantId tenantId, String reason,
            Map<String, String> attributes) {
        this(type, outcome, actorUserId, tenantId, reason, attributes, null, null, null, null, null);
    }

    private static String cut(String value) {
        return value == null || value.length() <= MAX_CHANGE_LENGTH ? value : value.substring(0, MAX_CHANGE_LENGTH);
    }

    /**
     * Prints the kind and the outcome only. A record can hold values chosen by a person, and debug logging prints
     * what a record's text form holds (ADR-0012), so the text form holds nothing typed.
     */
    @Override
    public String toString() {
        return "AuditRecord[" + type + ", " + outcome + "]";
    }

    /** A record without attributes, user, tenant or reason. */
    public static AuditRecord of(String type, AuditOutcome outcome) {
        return new AuditRecord(type, outcome, null, null, null, Map.of());
    }

    /** The same record about a user. */
    public AuditRecord forUser(UUID userId) {
        return new AuditRecord(type, outcome, userId, tenantId, reason, attributes, objectKey, recordId, oldValue,
                newValue, source);
    }

    /** The same record for a tenant (null for the platform host). */
    public AuditRecord inTenant(TenantId tenant) {
        return new AuditRecord(type, outcome, actorUserId, tenant, reason, attributes, objectKey, recordId, oldValue,
                newValue, source);
    }

    /** The same record with an internal reason. */
    public AuditRecord because(String internalReason) {
        return new AuditRecord(type, outcome, actorUserId, tenantId, internalReason, attributes, objectKey, recordId,
                oldValue, newValue, source);
    }

    /** The same record with one more attribute. */
    public AuditRecord with(String key, String value) {
        Map<String, String> more = new LinkedHashMap<>(attributes);
        more.put(key, value);
        return new AuditRecord(type, outcome, actorUserId, tenantId, reason, more, objectKey, recordId, oldValue,
                newValue, source);
    }

    /** The same record about an object and, when known, one record of it. */
    public AuditRecord onObject(String object, String record) {
        return new AuditRecord(type, outcome, actorUserId, tenantId, reason, attributes, object, record, oldValue,
                newValue, source);
    }

    /** The same record with what changed: the value before and after, short values or keys, never typed text. */
    public AuditRecord changing(String before, String after) {
        return new AuditRecord(type, outcome, actorUserId, tenantId, reason, attributes, objectKey, recordId, before,
                after, source);
    }

    /** The same record with its origin. */
    public AuditRecord from(AuditSource origin) {
        return new AuditRecord(type, outcome, actorUserId, tenantId, reason, attributes, objectKey, recordId,
                oldValue, newValue, origin);
    }
}
