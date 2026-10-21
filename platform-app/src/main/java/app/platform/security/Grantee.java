package app.platform.security;

import java.util.Objects;
import java.util.UUID;

/**
 * Whom an explicit share of a record is addressed to: a kind and an identifier (ADR-0178, ADR-0185). The statement that
 * reads the shares matches on both, never on the identifier alone, so the match does not depend on the identifiers of
 * users, groups and roles living in different tables.
 *
 * <p>What each kind matches, for a member (the only place this is written down; {@code Admission.SharedWith} carries
 * the pairs a member matches and the statement compares them with the stored shares):
 * <ul>
 *   <li>{@link Kind#USER}: the member's own user.</li>
 *   <li>{@link Kind#GROUP}: a public group the member is in, directly or through nested groups.</li>
 *   <li>{@link Kind#ROLE}: the member's own role, and no other.</li>
 *   <li>{@link Kind#ROLE_AND_SUBORDINATES}: any role in the member's own chain (their role and every role above it), so
 *       a share on a role reaches the members of that role and of every role below it.</li>
 * </ul>
 *
 * @param kind the kind of grantee
 * @param id the user, group or role
 */
public record Grantee(Kind kind, UUID id) implements Comparable<Grantee> {

    /** The kinds of grantee a share may name. The names are what the share table stores. */
    public enum Kind {
        /** One user. */
        USER,
        /** A public group and the groups inside it. */
        GROUP,
        /** The members whose own role it is. */
        ROLE,
        /** The members of a role and of every role below it. */
        ROLE_AND_SUBORDINATES;

        /** The kind of the stored name, or null when it is not one. */
        public static Kind ofName(String name) {
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            return null;
        }

        /** Whether this is one of the two kinds that name a role. */
        public boolean isRole() {
            return this == ROLE || this == ROLE_AND_SUBORDINATES;
        }
    }

    /** Checks both parts. */
    public Grantee {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /** A user. */
    public static Grantee user(UUID id) {
        return new Grantee(Kind.USER, id);
    }

    /** A public group. */
    public static Grantee group(UUID id) {
        return new Grantee(Kind.GROUP, id);
    }

    /** A role alone. */
    public static Grantee role(UUID id) {
        return new Grantee(Kind.ROLE, id);
    }

    /** A role and every role below it. */
    public static Grantee roleAndSubordinates(UUID id) {
        return new Grantee(Kind.ROLE_AND_SUBORDINATES, id);
    }

    @Override
    public int compareTo(Grantee other) {
        int byKind = kind.compareTo(other.kind);
        return byKind != 0 ? byKind : id.compareTo(other.id);
    }

    /** Prints the kind only: the identifier belongs to the organization. */
    @Override
    public String toString() {
        return "Grantee[" + kind + "]";
    }
}
