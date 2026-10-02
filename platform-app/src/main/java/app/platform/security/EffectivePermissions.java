package app.platform.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * The effective-permission calculator (ADR-0040): what one member may do, from what they hold. A pure function: it
 * reads nothing, writes nothing and gives the same answer for the same input, in any order of the input.
 *
 * <h2>The algorithm</h2>
 * <ol>
 *   <li><strong>Profile.</strong> A member has one profile. It contributes its abilities (all of them for a profile
 * with       full access) <em>only while the member holds the licence the profile needs</em>; without it the profile
 *       contributes nothing. This is how a licence (the right to occupy a seat) reaches abilities without being one:
 * the       licence decides whether the profile counts, the profile decides what it holds (ADR-0039).</li>
 *   <li><strong>Access policies.</strong> Every access policy assigned to the member contributes all its abilities. A
 *       policy that needs a licence cannot be assigned without one, so its licence is not asked again here.</li>
 *   <li><strong>Individual grants.</strong> Every ability granted to the member directly is contributed.</li>
 *   <li><strong>Union.</strong> The result is the union of the three. There is no deny rule: nothing takes an ability
 *       away except removing the thing that gave it. This is what makes the result independent of the order of the
 *       inputs and of how they are grouped, and it is how the written design describes it (profile plus permission
 *       sets plus individual grants).</li>
 *   <li><strong>The role hierarchy takes no part.</strong> A role only decides which records a member may see; nothing
 *       about roles is an input here, so no role, cycle or hierarchy change can alter a result.</li>
 * </ol>
 *
 * <p>Properties the tests check on random input: the order of the policies and of their abilities does not matter;
 * adding
 * a grant, a policy or an ability to a policy never removes an ability from the result; removing and adding the same
 * thing again gives the same result; no input gives no abilities; a profile without its licence contributes nothing.
 */
public final class EffectivePermissions {

    /**
     * What a member's profile contributes.
     *
     * @param fullAccess whether the profile always holds every ability the platform knows (the administrator profile)
     * @param abilities the abilities the profile lists (ignored when it has full access)
     * @param licensed whether the member holds the licence the profile needs
     */
    public record ProfileInput(boolean fullAccess, Set<Ability> abilities, boolean licensed) {

        public ProfileInput {
            abilities = abilities == null ? Set.of() : Set.copyOf(abilities);
        }

        /** A member without a profile. */
        public static ProfileInput none() {
            return new ProfileInput(false, Set.of(), false);
        }
    }

    private EffectivePermissions() {
    }

    /**
     * Computes the abilities of one member.
     *
     * @param profile what the member's profile contributes
     * @param policies the abilities of each access policy assigned to the member
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
        return java.util.Collections.unmodifiableSet(result);
    }
}
