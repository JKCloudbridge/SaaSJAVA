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
 */
public record ShippedEntryView(Kind kind, String label, String objectApiName, String pageKey, String readsObject) {

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
