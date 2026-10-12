package app.platform.metadata.internal;

import java.util.Arrays;
import java.util.Optional;

/**
 * One intended change to published metadata (ADR-0066): what to do, to which object and item, and the request that
 * describes it, kept as the JSON of the same request record the live endpoint takes. A change is the unit of a change
 * set, of a quick change and of the undo list of a release, so all three are judged by exactly the same rules.
 *
 * @param kind what the change does
 * @param object the API name of the object it is about, empty for an application
 * @param item the API name of the field, record type, layout, record page, validation rule or application it is
 *        about (for a layout assignment its key), or null
 * @param payload the JSON of the request ({@code {}} for a removal)
 */
record Change(Kind kind, String object, String item, String payload) {

    /** What a change can do. */
    enum Kind {
        CREATE_OBJECT(Entity.OBJECT, false),
        UPDATE_OBJECT(Entity.OBJECT, false),
        DELETE_OBJECT(Entity.OBJECT, false),
        CREATE_FIELD(Entity.FIELD, false),
        UPDATE_FIELD(Entity.FIELD, true),
        DELETE_FIELD(Entity.FIELD, true),
        CREATE_RECORD_TYPE(Entity.RECORD_TYPE, false),
        UPDATE_RECORD_TYPE(Entity.RECORD_TYPE, true),
        DELETE_RECORD_TYPE(Entity.RECORD_TYPE, true),
        CREATE_LAYOUT(Entity.LAYOUT, false),
        UPDATE_LAYOUT(Entity.LAYOUT, true),
        DELETE_LAYOUT(Entity.LAYOUT, true),
        CREATE_RECORD_PAGE(Entity.RECORD_PAGE, false),
        UPDATE_RECORD_PAGE(Entity.RECORD_PAGE, true),
        DELETE_RECORD_PAGE(Entity.RECORD_PAGE, true),
        CREATE_APPLICATION(Entity.APPLICATION, false),
        UPDATE_APPLICATION(Entity.APPLICATION, true),
        DELETE_APPLICATION(Entity.APPLICATION, true),
        /** A layout assignment is named by its key, also when it is made: the key is what the change is about. */
        CREATE_LAYOUT_ASSIGNMENT(Entity.LAYOUT_ASSIGNMENT, true),
        UPDATE_LAYOUT_ASSIGNMENT(Entity.LAYOUT_ASSIGNMENT, true),
        DELETE_LAYOUT_ASSIGNMENT(Entity.LAYOUT_ASSIGNMENT, true),
        CREATE_VALIDATION_RULE(Entity.VALIDATION_RULE, false),
        UPDATE_VALIDATION_RULE(Entity.VALIDATION_RULE, true),
        DELETE_VALIDATION_RULE(Entity.VALIDATION_RULE, true),
        /** A record type assignment is named by its key (the record type, a tilde, the profile), also when made. */
        CREATE_RECORD_TYPE_ASSIGNMENT(Entity.RECORD_TYPE_ASSIGNMENT, true),
        UPDATE_RECORD_TYPE_ASSIGNMENT(Entity.RECORD_TYPE_ASSIGNMENT, true),
        DELETE_RECORD_TYPE_ASSIGNMENT(Entity.RECORD_TYPE_ASSIGNMENT, true);

        private final Entity entity;
        private final boolean namesItem;

        Kind(Entity entity, boolean namesItem) {
            this.entity = entity;
            this.namesItem = namesItem;
        }

        /** What kind of definition the change is about. */
        Entity entity() {
            return entity;
        }

        /** Whether the change names the item it is about (a creation carries the name inside, except an assignment). */
        boolean namesItem() {
            return namesItem;
        }

        boolean isCreate() {
            return name().startsWith("CREATE_");
        }

        boolean isDelete() {
            return name().startsWith("DELETE_");
        }

        static Optional<Kind> fromCode(String code) {
            return Arrays.stream(values()).filter(kind -> kind.name().equals(code)).findFirst();
        }
    }

    /** The kinds of definition. */
    enum Entity { OBJECT, FIELD, RECORD_TYPE, LAYOUT, RECORD_PAGE, APPLICATION, LAYOUT_ASSIGNMENT, VALIDATION_RULE,
        RECORD_TYPE_ASSIGNMENT }
}
