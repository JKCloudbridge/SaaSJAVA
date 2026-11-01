package app.platform.integration.adapter;

/**
 * How a long-running operation on the other side stands, as the answer to a question about it says (Sprint 29,
 * ADR-0233).
 */
public enum JobState {

    /** Not finished; ask again later. */
    RUNNING,

    /** Finished, and the entity was accepted. */
    DONE,

    /** Finished without success; the delivery will not succeed by waiting. */
    FAILED
}
