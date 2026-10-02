package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.EffectivePermissions.ProfileInput;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The effective-permission calculator on thousands of random inputs (ADR-0040, ADR-0041). Each property is a sentence
 * of the documented algorithm. The tests were checked to fail when the calculator is changed on purpose (a grant
 * ignored, the licence ignored, the order of the policies mattering): see {@code Sprint 7.md}.
 */
class EffectivePermissionsPropertyTest {

    @Provide
    Arbitrary<Set<Ability>> abilitySets() {
        return Arbitraries.of(Ability.class).set().ofMaxSize(Ability.values().length);
    }

    @Provide
    Arbitrary<List<Set<Ability>>> policyLists() {
        return abilitySets().list().ofMaxSize(6);
    }

    @Provide
    Arbitrary<ProfileInput> profiles() {
        return Arbitraries.of(true, false).flatMap(full -> Arbitraries.of(true, false).flatMap(licensed ->
                abilitySets().map(abilities -> new ProfileInput(full, abilities, licensed))));
    }

    /** The definition written out again, independently of the implementation. */
    private static Set<Ability> oracle(ProfileInput profile, Collection<? extends Collection<Ability>> policies,
            Collection<Ability> grants) {
        Set<Ability> expected = EnumSet.noneOf(Ability.class);
        for (Ability ability : Ability.values()) {
            boolean fromProfile = profile.licensed() && (profile.fullAccess() || profile.abilities().contains(ability));
            boolean fromPolicy = policies.stream().anyMatch(policy -> policy.contains(ability));
            if (fromProfile || fromPolicy || grants.contains(ability)) {
                expected.add(ability);
            }
        }
        return expected;
    }

    @Property
    void theResultIsTheUnionOfTheThreeSources(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<Set<Ability>> policies, @ForAll("abilitySets") Set<Ability> grants) {
        assertThat(EffectivePermissions.compute(profile, policies, grants))
                .isEqualTo(oracle(profile, policies, grants));
    }

    @Property
    void theOrderOfThePoliciesDoesNotMatter(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<Set<Ability>> policies, @ForAll("abilitySets") Set<Ability> grants,
            @ForAll Random random) {
        List<Set<Ability>> shuffled = new ArrayList<>(policies);
        Collections.shuffle(shuffled, random);

        assertThat(EffectivePermissions.compute(profile, shuffled, grants))
                .isEqualTo(EffectivePermissions.compute(profile, policies, grants));
    }

    @Property
    void addingAGrantNeverRemovesAnAbility(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<Set<Ability>> policies, @ForAll("abilitySets") Set<Ability> grants,
            @ForAll Ability extra) {
        Set<Ability> more = EnumSet.noneOf(Ability.class);
        more.addAll(grants);
        more.add(extra);

        Set<Ability> before = EffectivePermissions.compute(profile, policies, grants);
        Set<Ability> after = EffectivePermissions.compute(profile, policies, more);

        assertThat(after).containsAll(before).contains(extra);
    }

    @Property
    void addingAnAccessPolicyNeverRemovesAnAbility(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<Set<Ability>> policies, @ForAll("abilitySets") Set<Ability> grants,
            @ForAll("abilitySets") Set<Ability> newPolicy) {
        List<Set<Ability>> more = new ArrayList<>(policies);
        more.add(newPolicy);

        assertThat(EffectivePermissions.compute(profile, more, grants))
                .containsAll(EffectivePermissions.compute(profile, policies, grants)).containsAll(newPolicy);
    }

    @Property
    void addingAnAbilityToAPolicyNeverRemovesAnAbility(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<Set<Ability>> policies, @ForAll("abilitySets") Set<Ability> grants,
            @ForAll Ability extra) {
        if (policies.isEmpty()) {
            return;
        }
        List<Set<Ability>> changed = new ArrayList<>(policies);
        Set<Ability> first = EnumSet.noneOf(Ability.class);
        first.addAll(changed.get(0));
        first.add(extra);
        changed.set(0, first);

        assertThat(EffectivePermissions.compute(profile, changed, grants))
                .containsAll(EffectivePermissions.compute(profile, policies, grants));
    }

    @Property
    void removingAPolicyAndAddingItAgainGivesTheSameResult(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<Set<Ability>> policies, @ForAll("abilitySets") Set<Ability> grants,
            @ForAll Random random) {
        if (policies.isEmpty()) {
            return;
        }
        List<Set<Ability>> changed = new ArrayList<>(policies);
        Set<Ability> removed = changed.remove(random.nextInt(changed.size()));
        changed.add(removed);

        assertThat(EffectivePermissions.compute(profile, changed, grants))
                .isEqualTo(EffectivePermissions.compute(profile, policies, grants));
    }

    @Property
    void removingAGrantThatNothingElseGivesRemovesExactlyThatAbility(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<Set<Ability>> policies, @ForAll("abilitySets") Set<Ability> grants,
            @ForAll Ability target) {
        Set<Ability> without = EnumSet.noneOf(Ability.class);
        without.addAll(grants);
        without.remove(target);
        Set<Ability> others = EffectivePermissions.compute(profile, policies, without);

        Set<Ability> all = EffectivePermissions.compute(profile, policies, grants);

        assertThat(all.contains(target)).isEqualTo(others.contains(target) || grants.contains(target));
        assertThat(others).containsAll(all.stream().filter(ability -> ability != target).toList());
    }

    @Property
    void nothingGivesNothing() {
        assertThat(EffectivePermissions.compute(ProfileInput.none(), List.of(), Set.of())).isEmpty();
        assertThat(EffectivePermissions.compute(new ProfileInput(true, Set.of(), false), List.of(), Set.of()))
                .as("a profile without its licence contributes nothing, even with full access").isEmpty();
    }

    @Property
    void aProfileWithoutItsLicenceContributesNothing(@ForAll("policyLists") List<Set<Ability>> policies,
            @ForAll("abilitySets") Set<Ability> grants, @ForAll("abilitySets") Set<Ability> profileAbilities,
            @ForAll boolean fullAccess) {
        Set<Ability> withoutLicence = EffectivePermissions.compute(
                new ProfileInput(fullAccess, profileAbilities, false), policies, grants);

        assertThat(withoutLicence).isEqualTo(
                EffectivePermissions.compute(ProfileInput.none(), policies, grants));
    }

    @Property
    void aLicensedProfileWithFullAccessHoldsEveryAbility(@ForAll("policyLists") List<Set<Ability>> policies,
            @ForAll("abilitySets") Set<Ability> grants) {
        assertThat(EffectivePermissions.compute(new ProfileInput(true, Set.of(), true), policies, grants))
                .isEqualTo(Ability.all());
    }

    @Property(tries = 1)
    void noRoleOrHierarchyIsAnInputOfTheCalculator() {
        Method compute = java.util.Arrays.stream(EffectivePermissions.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("compute")).findFirst().orElseThrow();

        assertThat(compute.getParameterTypes())
                .as("only the profile, the access policies and the individual grants decide abilities")
                .containsExactly(ProfileInput.class, Collection.class, Collection.class);
    }
}
