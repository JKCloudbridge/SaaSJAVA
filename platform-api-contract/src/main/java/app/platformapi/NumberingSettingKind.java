package app.platformapi;

/** The category-specific setting or either fallback variant of a number series. */
public enum NumberingSettingKind {
    /** A format for one category; empty selector for a series without a selector. */
    CATEGORY,
    /** Fallback when the optional part is present. */
    DEFAULT,
    /** Fallback when the optional part is absent. */
    WITHOUT_OPTIONAL
}
