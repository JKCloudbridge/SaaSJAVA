package app.platform.metadata.internal;

import java.util.UUID;

/**
 * An organization tried to change something the platform defines (a standard object or field, or a system field). The
 * work stops, and {@link MetadataAdministration} records the refusal after the transaction ended and tells the caller
 * in words (ADR-0059). It is not an {@code ApiException} on purpose: a refusal for lack of an ability is recorded as
 * such by the layer below, and this one is a different refusal with a different reason.
 */
final class ProtectedDefinition extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient UUID actor;
    private final String action;
    private final String object;

    ProtectedDefinition(UUID actor, String action, String object) {
        super("A definition of the platform cannot be changed by an organization");
        this.actor = actor;
        this.action = action;
        this.object = object;
    }

    UUID actor() {
        return actor;
    }

    String action() {
        return action;
    }

    String object() {
        return object;
    }
}
