package app.platform.sharedkernel.audit;

/** Where an audit record came from (audit v1, ADR-0054). */
public enum AuditSource {

    /** A person's request to the API. */
    API,

    /** An event taken from the outbox by the audit module. */
    EVENT,

    /** A scheduled job of the platform (retention). */
    SCHEDULER,

    /** Anything else the platform did on its own (a start-up task, a system scope). */
    SYSTEM
}
