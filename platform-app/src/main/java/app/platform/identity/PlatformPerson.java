package app.platform.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * One platform role held by one person, as the platform administrators see it.
 *
 * @param assignmentId the role assignment (what is revoked)
 * @param userId the person's account
 * @param email the person's address
 * @param displayName the person's name
 * @param role the role
 * @param since when the role was granted
 */
public record PlatformPerson(UUID assignmentId, UUID userId, String email, String displayName, PlatformRole role,
        Instant since) {

    @Override
    public String toString() {
        // The address is personal data: it stays out of any log line that prints this record.
        return "PlatformPerson[redacted]";
    }
}
