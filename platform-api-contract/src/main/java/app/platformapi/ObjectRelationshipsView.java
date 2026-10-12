package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Every relationship of one object, in both directions (Sprint 11).
 *
 * @param parents the objects this object points at (its lookup and master-detail fields)
 * @param children the objects that point at this one, which appear as lists on it
 * @param manyToMany the objects related through a junction object
 */
public record ObjectRelationshipsView(@NotNull List<RelationshipView> parents, @NotNull List<RelationshipView> children,
        @NotNull List<RelationshipView> manyToMany) {

    /** Copies the lists. */
    public ObjectRelationshipsView {
        parents = parents == null ? List.of() : List.copyOf(parents);
        children = children == null ? List.of() : List.copyOf(children);
        manyToMany = manyToMany == null ? List.of() : List.copyOf(manyToMany);
    }
}
