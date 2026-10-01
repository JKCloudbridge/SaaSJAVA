package app.platform.sharedkernel.secrets;

import java.time.Instant;
import java.util.Objects;

/**
 * Non-sensitive facts about a stored secret. This is the only view of a secret that may be shown to
 * administrators or written to audit records.
 *
 * @param ref the secret's address
 * @param version monotonically increasing; a new version is created on every write
 * @param createdAt when the first version was written
 * @param updatedAt when the current version was written
 */
public record SecretMetadata(SecretRef ref, int version, Instant createdAt, Instant updatedAt) {

    public SecretMetadata {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }
}
