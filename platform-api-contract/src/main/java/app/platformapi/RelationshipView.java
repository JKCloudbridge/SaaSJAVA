package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One relationship between two objects as seen from one of them (Sprint 11). A relationship is not stored on its own:
 * it is read from a lookup or master-detail field (and, for many-to-many, from a junction object that has two
 * master-detail fields), so there is one source of truth.
 *
 * @param type {@code MANY_TO_ONE} or {@code ONE_TO_ONE} (a parent, seen from the child), {@code ONE_TO_MANY} or
 *        {@code ONE_TO_ONE} (children, seen from the parent), {@code MANY_TO_MANY} (through a junction object)
 * @param fieldType {@code LOOKUP} or {@code MASTER_DETAIL}; for many-to-many the type of the junction's field that
 *        points at the asked object
 * @param childObject the object that holds the field (the junction for many-to-many)
 * @param field the API name of the field on {@code childObject}
 * @param fieldLabel the label of that field
 * @param parentObject the object the field points at
 * @param otherObject the object at the other end as seen from the asked object (for many-to-many, the object on the
 *        junction's other side)
 * @param viaObject the junction object of a many-to-many relationship, or null
 * @param onDelete {@code CLEAR}, {@code REFUSE} or {@code CASCADE}: what happens to the child when the parent is
 *        removed
 * @param reparentable whether a detail may be moved to another master
 * @param required whether a child must have a parent
 * @param listLabel the label of the list of children on the parent
 */
public record RelationshipView(@NotNull String type, @NotNull String fieldType, @NotNull String childObject,
        @NotNull String field, @NotNull String fieldLabel, @NotNull String parentObject, @NotNull String otherObject,
        String viaObject, @NotNull String onDelete, @NotNull Boolean reparentable, @NotNull Boolean required,
        @NotNull String listLabel) {
}
