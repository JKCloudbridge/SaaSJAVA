package app.platform.security;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The effective-permission calculator (ADR-0040, extended by ADR-0049): what one member may do, from what they hold. A
 * pure function: it reads nothing, writes nothing and gives the same answer for the same input, in any order of the
 * input.
 *
 * <h2>The algorithm</h2>
 * <ol>
 *   <li><strong>Profile.</strong> A member has one profile. It contributes its abilities and its permissions on objects
 *       and fields (everything, for a profile with full access) <em>only while the member holds the licence the profile
 *       needs</em>; without it the profile contributes nothing. This is how a licence (the right to occupy a seat)
 *       reaches permissions without being one: the licence decides whether the profile counts, the profile decides
 *       what it holds (ADR-0039).</li>
 *   <li><strong>Access policies.</strong> Every access policy that reaches the member, assigned to them or given to a
 *       group they are in (directly or through nested groups, ADR-0047), contributes all it holds, abilities and
 *       permissions on objects and fields alike. A policy that needs a licence counts only while the member holds a
 *       licence of that type: their own for it, or the one of their profile when the type is the same (ADR-0046).</li>
 *   <li><strong>Individual grants.</strong> Everything granted to the member directly is contributed.</li>
 *   <li><strong>Union.</strong> The result is the union of the three. There is no deny rule: nothing takes anything
 *   away
 *       except removing the thing that gave it. This is what makes the result independent of the order of the inputs
 *       and
 *       of how they are grouped, and it is how the written design describes it (profile plus permission sets plus
 *       individual grants).</li>
 *   <li><strong>The role hierarchy takes no part.</strong> A role only decides which records a member may see; nothing
 *       about roles is an input here, so no role, cycle or hierarchy change can alter a result.</li>
 * </ol>
 *
 * <p>The implications between actions (update implies read, edit implies read, modify-all implies the rest) are
 * answered by {@link DataAccess}; they are monotone, so applying them after the union gives the same result as
 * applying them to each part first.
 *
 * <p>Properties the tests check on random input: the order of the policies does not matter; adding a grant, a policy or
 * a permission never removes anything from the result; removing and adding the same thing again gives the same result;
 * no input gives nothing; a profile without its licence contributes nothing; a policy without its licence contributes
 * nothing.
 */
public final class EffectivePermissions {

    /**
     * What one container holds: abilities and permissions on data.
     *
     * @param abilities the abilities
     * @param data the permissions on objects and fields
     */
    public record Holding(Set<Ability> abilities, DataAccess data) {

        /** Copies what is given. */
        public Holding {
            abilities = abilities == null ? Set.of() : Set.copyOf(abilities);
            data = data == null ? DataAccess.none() : data;
        }

        /** A holding of abilities only. */
        public static Holding ofAbilities(Collection<Ability> abilities) {
            return new Holding(Set.copyOf(abilities), DataAccess.none());
        }
    }

    /**
     * What a member's profile contributes.
     *
     * @param fullAccess whether the profile always holds every ability and every permission (the administrator profile)
     * @param abilities the abilities the profile lists (ignored when it has full access)
     * @param data the permissions on objects and fields the profile lists (ignored when it has full access)
     * @param licensed whether the member holds the licence the profile needs
     */
    public record ProfileInput(boolean fullAccess, Set<Ability> abilities, DataAccess data, boolean licensed) {

        /** Copies what is given. */
        public ProfileInput {
            abilities = abilities == null ? Set.of() : Set.copyOf(abilities);
            data = data == null ? DataAccess.none() : data;
        }

        /** A profile that lists abilities only. */
        public ProfileInput(boolean fullAccess, Set<Ability> abilities, boolean licensed) {
            this(fullAccess, abilities, DataAccess.none(), licensed);
        }

        /** A member without a profile. */
        public static ProfileInput none() {
            return new ProfileInput(false, Set.of(), DataAccess.none(), false);
        }
    }

    /**
     * An access policy that reaches the member.
     *
     * @param holding what the policy holds
     * @param licensed whether the policy counts: it needs no licence, or the member holds a licence of the type it
     * needs
     */
    public record PolicyInput(Holding holding, boolean licensed) {
    }

    /**
     * What a member may do.
     *
     * @param abilities the abilities
     * @param data the permissions on objects and fields
     */
    public record Effective(Set<Ability> abilities, DataAccess data) {

        /** Copies what is given. */
        public Effective {
            Set<Ability> copy = EnumSet.noneOf(Ability.class);
            copy.addAll(abilities);
            abilities = Collections.unmodifiableSet(copy);
            data = data == null ? DataAccess.none() : data;
        }
    }

    private EffectivePermissions() {
    }

    /**
     * Computes what one member may do.
     *
     * @param profile what the member's profile contributes
     * @param policies every access policy that reaches the member (assigned or through groups)
     * @param grants what is granted to the member directly
     * @return the union, as unmodifiable values
     */
    public static Effective computeAll(ProfileInput profile, Collection<PolicyInput> policies,
            Collection<Holding> grants) {
        Set<Ability> abilities = EnumSet.noneOf(Ability.class);
        DataAccess data = DataAccess.none();
        if (profile.licensed()) {
            if (profile.fullAccess()) {
                abilities.addAll(Ability.all());
                data = data.union(DataAccess.fullAccess());
            } else {
                abilities.addAll(profile.abilities());
                data = data.union(profile.data());
            }
        }
        for (PolicyInput policy : policies) {
            if (policy.licensed()) {
                abilities.addAll(policy.holding().abilities());
                data = data.union(policy.holding().data());
            }
        }
        for (Holding grant : grants) {
            abilities.addAll(grant.abilities());
            data = data.union(grant.data());
        }
        return new Effective(abilities, data);
    }

    /**
     * Computes the abilities of one member (the abilities part of {@link #computeAll}; every policy given counts).
     *
     * @param profile what the member's profile contributes
     * @param policies the abilities of each access policy that counts
     * @param grants the abilities granted to the member directly
     * @return the union, as an unmodifiable set
     */
    public static Set<Ability> compute(ProfileInput profile, Collection<? extends Collection<Ability>> policies,
            Collection<Ability> grants) {
        Set<Ability> result = EnumSet.noneOf(Ability.class);
        if (profile.licensed()) {
            result.addAll(profile.fullAccess() ? Ability.all() : profile.abilities());
        }
        policies.forEach(result::addAll);
        result.addAll(grants);
        return Collections.unmodifiableSet(result);
    }
}
