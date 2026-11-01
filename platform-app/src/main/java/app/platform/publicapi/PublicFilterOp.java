package app.platform.publicapi;

/**
 * The comparisons of a filter (Sprint 30, ADR-0245). A value is added in a later release of the contract only as a
 * new operation; a value is never removed or renamed inside version 1.
 */
public enum PublicFilterOp {

    /** All the items. */
    AND,
    /** Any of the items. */
    OR,
    /** The field has this value. */
    EQ,
    /** The field has a value and it is another one. */
    NE,
    /** Greater than. */
    GT,
    /** Greater than or equal. */
    GTE,
    /** Less than. */
    LT,
    /** Less than or equal. */
    LTE,
    /** From the first value to the second, both included. */
    BETWEEN,
    /** One of the values. */
    IN,
    /** The field has no value. */
    IS_EMPTY,
    /** The field has a value. */
    IS_NOT_EMPTY,
    /** A text that starts with the value. */
    STARTS_WITH,
    /** A text that contains the value. */
    CONTAINS
}
