package app.platform.sharedkernel.notice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A request to tell one member of the current organization something in the application (Sprint 25, ADR-0154). It
 * holds facts and never a message: the kind says what happened, the facts are identifiers and API names that
 * the kind lists, and the words are built when the notice is read. A value of a record, a typed text or a link
 * is refused when the request is built, so a mistake fails in a test and not in a stored row.
 *
 * @param kind what happened
 * @param recipientId the member (user identifier) to tell
 * @param objectApiName the object of the record the notice is about, or null
 * @param recordId the record the notice is about, or null; given together with the object
 * @param facts the facts of the kind (API names and stable keys only)
 * @param dedupeKey the key that makes the notice happen once: a second request with the same key for the same
 *        recipient creates nothing, however often the cause is retried, replayed or delivered again
 */
public record NoticeRequest(NoticeKind kind, UUID recipientId, String objectApiName, UUID recordId,
        Map<String, String> facts, String dedupeKey) {

    /** The shape of a fact value: an API name, an identifier or a stable key; never free text. */
    public static final Pattern FACT_VALUE = Pattern.compile("[A-Za-z0-9_.\\-]{1,80}");

    private static final Pattern OBJECT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9:_.\\-]{1,160}");

    /** Checks the shape of every part and copies the facts. */
    public NoticeRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        if (!KEY.matcher(dedupeKey).matches()) {
            throw new IllegalArgumentException("A notice key is letters, digits and a few marks");
        }
        if ((objectApiName == null) != (recordId == null)) {
            throw new IllegalArgumentException("A notice names a record with its object, or neither");
        }
        if (objectApiName != null && !OBJECT.matcher(objectApiName).matches()) {
            throw new IllegalArgumentException("A notice names an object by its API name");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (facts != null) {
            facts.forEach((name, value) -> {
                if (!kind.facts().contains(name)) {
                    throw new IllegalArgumentException("A notice of kind " + kind + " does not carry the fact " + name);
                }
                if (value == null || !FACT_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("A fact of a notice is an API name or a stable key");
                }
                copy.put(name, value);
            });
        }
        facts = Map.copyOf(copy);
    }

    /** Prints the kind only: a notice is about a person. */
    @Override
    public String toString() {
        return "NoticeRequest[" + kind + "]";
    }
}
