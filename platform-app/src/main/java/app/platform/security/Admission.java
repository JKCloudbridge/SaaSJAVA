package app.platform.security;

import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A closed description of which records a member may reach with one kind of action: the one thing the security module
 * hands to the data module for record-level security (ADR-0115). It is a small tree of conditions a record must meet;
 * there is no text in it that is run and no name of a table.
 *
 * <p>The meaning, which the data module turns into one statement and which is also the specification the tests hold
 * the statement to (ADR-0118):
 * <ul>
 *   <li>{@link Everything}: every record of the object.</li>
 *   <li>{@link Nothing}: no record.</li>
 *   <li>{@link OwnedBy}: the records whose owner is one of the users.</li>
 *   <li>{@link SharedWith}: the records that are shared, at least at the level, with one of the grantees (a user, a
 *       public group or a role, each matched by kind and identifier).</li>
 *   <li>{@link Meets}: the records that meet a closed condition on their fields (a criteria-based sharing rule).</li>
 *   <li>{@link AnyOf}: the records that any one of the parts admits. It holds two or more parts that are neither
 *       {@code Everything}, {@code Nothing} nor another {@code AnyOf}: {@link #anyOf} makes sure of that.</li>
 * </ul>
 *
 * <p>No value of the tree is ever printed: the identifiers and the condition belong to the organization.
 */
public sealed interface Admission {

    /** Every record. */
    record Everything() implements Admission {
    }

    /** No record. */
    record Nothing() implements Admission {
    }

    /**
     * Records admitted by any of the parts.
     *
     * @param parts two or more conditions that are not combinations
     */
    record AnyOf(List<Admission> parts) implements Admission {

        /** Copies the list. */
        public AnyOf {
            parts = List.copyOf(parts);
        }

        @Override
        public String toString() {
            return "AnyOf[" + parts.size() + "]";
        }
    }

    /**
     * Records owned by one of the users.
     *
     * @param owners at least one user identifier
     */
    record OwnedBy(Set<UUID> owners) implements Admission {

        /** Copies the set. */
        public OwnedBy {
            owners = Set.copyOf(owners);
        }

        @Override
        public String toString() {
            return "OwnedBy[" + owners.size() + "]";
        }
    }

    /**
     * Records shared with one of the grantees at least at the level.
     *
     * @param grantees the grantees the member matches: their own user, every group they are in, their own role (as a
     *        {@link Grantee.Kind#ROLE} grantee) and every role of their chain (as
     *        {@link Grantee.Kind#ROLE_AND_SUBORDINATES} grantees). A share matches when its kind and identifier are
     *        one of these pairs.
     * @param atLeast the level the share must have
     */
    record SharedWith(Set<Grantee> grantees, ShareLevel atLeast) implements Admission {

        /** Copies the set. */
        public SharedWith {
            grantees = Set.copyOf(grantees);
        }

        @Override
        public String toString() {
            return "SharedWith[" + grantees.size() + ", " + atLeast + "]";
        }
    }

    /**
     * Records that meet the condition of a criteria-based sharing rule.
     *
     * @param rule the rule, so that a corpus or a report can name it
     * @param criterion the condition: the query filter model, checked against the metadata when the rule was saved
     */
    record Meets(UUID rule, QueryFilterNode criterion) implements Admission {

        @Override
        public String toString() {
            return "Meets[" + rule + "]";
        }
    }

    /** Every record. */
    static Admission everything() {
        return new Everything();
    }

    /** No record. */
    static Admission nothing() {
        return new Nothing();
    }

    /**
     * Combines conditions into the simplest tree that admits the same records: {@code Everything} wins, {@code Nothing}
     * and nested combinations disappear, the owners of several {@link OwnedBy} become one, so do the grantees of the
     * {@link SharedWith} of one level, and a single remaining part stands alone.
     */
    static Admission anyOf(Collection<? extends Admission> parts) {
        Set<UUID> owners = new TreeSet<>();
        Map<ShareLevel, Set<Grantee>> shared = new LinkedHashMap<>();
        List<Meets> criteria = new ArrayList<>();
        List<Admission> pending = new ArrayList<>(parts);
        for (int index = 0; index < pending.size(); index++) {
            switch (pending.get(index)) {
                case Everything everything -> {
                    return everything;
                }
                case Nothing nothing -> {
                    // adds nothing
                }
                case AnyOf nested -> pending.addAll(nested.parts());
                case OwnedBy owned -> owners.addAll(owned.owners());
                case SharedWith with -> shared.computeIfAbsent(with.atLeast(), level -> new TreeSet<>())
                        .addAll(with.grantees());
                case Meets meets -> criteria.add(meets);
            }
        }
        List<Admission> merged = new ArrayList<>();
        if (!owners.isEmpty()) {
            merged.add(new OwnedBy(owners));
        }
        // One part per level, in the order the levels were met, so that the same parts always give the same tree.
        shared.forEach((level, grantees) -> merged.add(new SharedWith(grantees, level)));
        merged.addAll(criteria);
        if (merged.isEmpty()) {
            return new Nothing();
        }
        return merged.size() == 1 ? merged.get(0) : new AnyOf(merged);
    }
}
