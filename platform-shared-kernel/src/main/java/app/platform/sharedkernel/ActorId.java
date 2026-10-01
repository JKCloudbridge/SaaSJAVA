package app.platform.sharedkernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of whoever performed an action, stored in the {@code created_by} and {@code updated_by} audit
 * columns (ADR-0010). Work done by the platform itself (migrations, scheduled jobs, start-up tasks) is
 * attributed to {@link #SYSTEM}; there is no foreign key to a user table because identities are owned by another
 * module.
 *
 * @param value the underlying identifier
 */
public record ActorId(UUID value) {

    /** The platform acting on its own behalf. The all-zero value can never be a generated identifier. */
    public static final ActorId SYSTEM = new ActorId(new UUID(0L, 0L));

    public ActorId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
