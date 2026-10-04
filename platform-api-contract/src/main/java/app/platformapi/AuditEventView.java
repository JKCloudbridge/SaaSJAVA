package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audit event as the audit viewer shows it (Sprint 9, ADR-0056).
 *
 * <p>Nothing typed by a person is in it except the names an organization chose for its own profiles, access
 * policies and groups, which are bounded. The internal reason of a refused sign-in is never in it.
 *
 * @param id the event
 * @param occurredAt when it happened
 * @param type what happened, for example {@code access.member.profile_set}
 * @param outcome SUCCESS, FAILURE or DENIED
 * @param actorUserId the person it is about or who did it, when known
 * @param source where it came from: API, EVENT, SCHEDULER or SYSTEM
 * @param objectKey the object it was about, when it was about data
 * @param recordId the record it was about, when known
 * @param oldValue the value before, when it was a change of a value
 * @param newValue the value after
 * @param reason the reason code of a refusal, for the kinds where it may be shown
 * @param attributes further facts (identifiers and bounded names)
 */
public record AuditEventView(
        @NotNull UUID id,
        @NotNull Instant occurredAt,
        @NotNull String type,
        @NotNull String outcome,
        UUID actorUserId,
        @NotNull String source,
        String objectKey,
        String recordId,
        String oldValue,
        String newValue,
        String reason,
        @NotNull Map<String, String> attributes) {

    /** Copies the attributes. */
    public AuditEventView {
        attributes = Map.copyOf(attributes);
    }

    @Override
    public String toString() {
        return "AuditEventView[redacted]";
    }
}
