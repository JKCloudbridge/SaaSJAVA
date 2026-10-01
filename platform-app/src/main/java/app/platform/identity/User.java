package app.platform.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A person's global identity (ADR-0019). One user may belong to several organizations later (memberships, Sprint 5);
 * the identity itself belongs to none. Carries no password material.
 *
 * @param id the identifier
 * @param email the normalized address (trimmed, lower case); unique among live users
 * @param displayName the name for people
 * @param status where the user is in their life
 * @param securityVersion rises whenever the user's outstanding tokens must stop working
 * @param emailVerifiedAt when the address was confirmed, or null
 * @param version optimistic concurrency counter
 */
public record User(UUID id, String email, String displayName, UserStatus status, long securityVersion,
        Instant emailVerifiedAt, long version) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(status, "status");
    }
}
