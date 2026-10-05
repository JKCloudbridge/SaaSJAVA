package app.platform.metadata;

/**
 * Who defined an object or a field (ADR-0058, ADR-0059).
 */
public enum DefinitionKind {

    /** Defined by the platform for one object, the same in every organization. Protected: nobody else changes it. */
    STANDARD,

    /** Defined by an organization for itself. */
    CUSTOM,

    /** A field every object has (identifier, running number, owner, who changed what and when). Protected. */
    SYSTEM
}
