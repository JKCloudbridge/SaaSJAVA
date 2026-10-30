package app.platform.integration.adapter;

/** What a send does to the entity on the external side (Sprint 28, ADR-0223). */
public enum Operation {
    /** The entity is made. */
    CREATE,
    /** The entity is changed. */
    UPDATE,
    /** The entity is removed. */
    DELETE
}
