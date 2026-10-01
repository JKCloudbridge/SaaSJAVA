package app.platform.sharedkernel.secrets;

import java.util.Optional;

/**
 * Cloud-neutral secrets store (decision D4). Interface only in Sprint 0; the first implementation
 * arrives in Sprint 27 and the concrete backing store is chosen per deployment target.
 *
 * <p>Contract every implementation must honour:
 *
 * <ul>
 *   <li><b>Isolation.</b> A {@link SecretRef} resolves only inside its own scope. A tenant scope can
 *       never read another tenant's or the platform's secrets.
 *   <li><b>No readback.</b> Plaintext is returned to server-side code at the moment of use only. It
 *       is never exposed through an API, an export, a log line, an exception message or a metadata
 *       table. Callers treat {@link SecretValue} as write-only toward users.
 *   <li><b>Versioning.</b> {@link #put} always creates a new version, which is how rotation works.
 *   <li><b>Audit.</b> Every {@code put}, {@code resolve} and {@code delete} is audited by the
 *       implementation, recording who and what but never the value.
 *   <li><b>Failure.</b> Store failures surface as unchecked exceptions that carry no secret
 *       material.
 * </ul>
 */
public interface SecretsStore {

    /** Resolves the current plaintext of a secret, if it exists. */
    Optional<SecretValue> resolve(SecretRef ref);

    /** Stores a new version of a secret (creating it if needed) and returns its metadata. */
    SecretMetadata put(SecretRef ref, SecretValue value);

    /** Returns non-sensitive facts about a secret, if it exists. */
    Optional<SecretMetadata> describe(SecretRef ref);

    /** Deletes a secret and all its versions. Deleting a missing secret is not an error. */
    void delete(SecretRef ref);
}
