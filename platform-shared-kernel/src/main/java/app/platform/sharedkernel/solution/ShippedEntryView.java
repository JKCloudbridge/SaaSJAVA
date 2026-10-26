package app.platform.sharedkernel.solution;

import java.util.Objects;

/**
 * One entry of a shipped application's navigation.
 *
 * @param kind what the entry opens
 * @param label what people read; for an {@code OBJECT} entry it may be empty, meaning the object's plural label
 * @param objectApiName for {@code OBJECT}, the object whose list opens
 * @param pageKey for {@code PLATFORM}, the key of a page the web application has registered for the solution
 * @param readsObject the object whose read permission shows the entry, or null: a {@code PLATFORM} entry carries no
 *        permission of its own, it is shown to the members who may read the object it names (an {@code OBJECT} entry
 *        is shown to the members who may read its object)
 * @param placeholder true for a function that is only announced (FA9, ADR-0203): the entry has a name and an identity
 *        but no page yet, and the browser draws the "being prepared" page for it
 */
public record ShippedEntryView(Kind kind, String label, String objectApiName, String pageKey, String readsObject,
        boolean placeholder) {

    /** An entry that is not a placeholder. */
    public ShippedEntryView(Kind kind, String label, String objectApiName, String pageKey, String readsObject) {
        this(kind, label, objectApiName, pageKey, readsObject, false);
    }

    /** The kinds of entry a shipped application may list. */
    public enum Kind {
        /** The list of an object's records. */
        OBJECT,
        /** A page the web application registered for the solution. */
        PLATFORM,
        /** A caption over the entries that follow. */
        HEADING
    }

    /** Checks what is given. */
    public ShippedEntryView {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
    }

    /** Prints the kind only. */
    @Override
    public String toString() {
        return "ShippedEntryView[" + kind + "]";
    }
}
