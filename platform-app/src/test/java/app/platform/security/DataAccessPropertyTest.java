package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.EffectivePermissions.Effective;
import app.platform.security.EffectivePermissions.Holding;
import app.platform.security.EffectivePermissions.PolicyInput;
import app.platform.security.EffectivePermissions.ProfileInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The extended calculator (abilities plus permissions on objects and fields, access policies that need a licence) on
 * thousands of random inputs (ADR-0040, ADR-0046, ADR-0049, ADR-0050). Each property is a sentence of the documented
 * algorithm, checked against a definition written out again here. They were checked to fail when the calculator was
 * changed on purpose (a policy's licence ignored, a grant ignored, the order of the policies mattering): see
 * {@code Sprint 8.md}.
 */
class DataAccessPropertyTest {

    private static final List<String> OBJECTS = List.of("object-a", "object-b", "object-c");
    private static final List<String> FIELDS = List.of("object-a.field-a", "object-a.field-b", "object-b.field-a",
            "object-c.field-a");

    @Provide
    Arbitrary<DataAccess> data() {
        Arbitrary<Map<String, Set<ObjectAction>>> objects = Arbitraries.of(OBJECTS).flatMap(key ->
                Arbitraries.of(ObjectAction.class).set().ofMinSize(1).ofMaxSize(3).map(actions -> Map.of(key, actions)))
                .list().ofMaxSize(3).map(DataAccessPropertyTest::mergeObjects);
        Arbitrary<Map<String, Set<FieldAction>>> fields = Arbitraries.of(FIELDS).flatMap(key ->
                Arbitraries.of(FieldAction.class).set().ofMinSize(1).ofMaxSize(2).map(actions -> Map.of(key, actions)))
                .list().ofMaxSize(4).map(DataAccessPropertyTest::mergeFields);
        return objects.flatMap(o -> fields.map(f -> DataAccess.of(o, f)));
    }

    @Provide
    Arbitrary<Holding> holdings() {
        return Arbitraries.of(Ability.class).set().ofMaxSize(3).flatMap(abilities ->
                data().map(data -> new Holding(abilities, data)));
    }

    @Provide
    Arbitrary<PolicyInput> policies() {
        return holdings().flatMap(holding -> Arbitraries.of(true, false).map(licensed ->
                new PolicyInput(holding, licensed)));
    }

    @Provide
    Arbitrary<List<PolicyInput>> policyLists() {
        return policies().list().ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<Holding>> grantLists() {
        return holdings().list().ofMaxSize(2);
    }

    @Provide
    Arbitrary<ProfileInput> profiles() {
        return Arbitraries.of(true, false).flatMap(full -> Arbitraries.of(true, false).flatMap(licensed ->
                holdings().map(holding -> new ProfileInput(full, holding.abilities(), holding.data(), licensed))));
    }

    private static Map<String, Set<ObjectAction>> mergeObjects(List<Map<String, Set<ObjectAction>>> parts) {
        Map<String, Set<ObjectAction>> result = new HashMap<>();
        parts.forEach(part -> part.forEach((key, actions) ->
                result.computeIfAbsent(key, k -> EnumSet.noneOf(ObjectAction.class)).addAll(actions)));
        return result;
    }

    private static Map<String, Set<FieldAction>> mergeFields(List<Map<String, Set<FieldAction>>> parts) {
        Map<String, Set<FieldAction>> result = new HashMap<>();
        parts.forEach(part -> part.forEach((key, actions) ->
                result.computeIfAbsent(key, k -> EnumSet.noneOf(FieldAction.class)).addAll(actions)));
        return result;
    }

    // ---- the definition, written out again ----

    private static boolean implies(ObjectAction held, ObjectAction asked) {
        if (held == asked) {
            return true;
        }
        return switch (held) {
            case MODIFY_ALL -> true;
            case CREATE, UPDATE, DELETE, VIEW_ALL -> asked == ObjectAction.READ;
            case READ -> false;
        };
    }

    private static boolean implies(FieldAction held, FieldAction asked) {
        return held == asked || (held == FieldAction.EDIT && asked == FieldAction.READ);
    }

    private static boolean grants(DataAccess data, String object, ObjectAction asked) {
        return data.everything() || data.objects().getOrDefault(object, Set.of()).stream()
                .anyMatch(held -> implies(held, asked));
    }

    private static boolean grantsField(DataAccess data, String field, FieldAction asked) {
        return data.everything() || data.fields().getOrDefault(field, Set.of()).stream()
                .anyMatch(held -> implies(held, asked));
    }

    /** What contributes: the licensed profile, the licensed policies, every grant. */
    private static List<DataAccess> contributions(ProfileInput profile, List<PolicyInput> policies,
            List<Holding> grants) {
        List<DataAccess> result = new ArrayList<>();
        if (profile.licensed()) {
            result.add(profile.fullAccess() ? DataAccess.fullAccess() : profile.data());
        }
        policies.stream().filter(PolicyInput::licensed).forEach(policy -> result.add(policy.holding().data()));
        grants.forEach(grant -> result.add(grant.data()));
        return result;
    }

    @Property
    void theResultIsTheUnionOfWhatContributes(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<PolicyInput> policies, @ForAll("grantLists") List<Holding> grants) {
        Effective result = EffectivePermissions.computeAll(profile, policies, grants);
        List<DataAccess> sources = contributions(profile, policies, grants);

        for (String object : OBJECTS) {
            for (ObjectAction action : ObjectAction.values()) {
                assertThat(result.data().allows(object, action)).as(object + " " + action)
                        .isEqualTo(sources.stream().anyMatch(source -> grants(source, object, action)));
            }
        }
        for (String field : FIELDS) {
            for (FieldAction action : FieldAction.values()) {
                assertThat(result.data().allowsField(field, action)).as(field + " " + action)
                        .isEqualTo(sources.stream().anyMatch(source -> grantsField(source, field, action)));
            }
        }
        for (Ability ability : Ability.values()) {
            boolean expected = (profile.licensed() && (profile.fullAccess() || profile.abilities().contains(ability)))
                    || policies.stream().anyMatch(policy -> policy.licensed()
                            && policy.holding().abilities().contains(ability))
                    || grants.stream().anyMatch(grant -> grant.abilities().contains(ability));
            assertThat(result.abilities().contains(ability)).as(ability.key()).isEqualTo(expected);
        }
    }

    @Property
    void theOrderOfThePoliciesAndGrantsDoesNotMatter(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<PolicyInput> policies, @ForAll("grantLists") List<Holding> grants,
            @ForAll Random random) {
        List<PolicyInput> shuffledPolicies = new ArrayList<>(policies);
        Collections.shuffle(shuffledPolicies, random);
        List<Holding> shuffledGrants = new ArrayList<>(grants);
        Collections.shuffle(shuffledGrants, random);

        assertThat(EffectivePermissions.computeAll(profile, shuffledPolicies, shuffledGrants))
                .isEqualTo(EffectivePermissions.computeAll(profile, policies, grants));
    }

    @Property
    void addingAPolicyOrAGrantNeverRemovesAnything(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<PolicyInput> policies, @ForAll("grantLists") List<Holding> grants,
            @ForAll("policies") PolicyInput extraPolicy, @ForAll("holdings") Holding extraGrant) {
        Effective before = EffectivePermissions.computeAll(profile, policies, grants);
        List<PolicyInput> morePolicies = new ArrayList<>(policies);
        morePolicies.add(extraPolicy);
        List<Holding> moreGrants = new ArrayList<>(grants);
        moreGrants.add(extraGrant);

        Effective after = EffectivePermissions.computeAll(profile, morePolicies, moreGrants);

        assertThat(after.abilities()).containsAll(before.abilities());
        assertThat(after.data().covers(before.data())).isTrue();
    }

    @Property
    void aPolicyWithoutItsLicenceContributesNothing(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<PolicyInput> policies, @ForAll("grantLists") List<Holding> grants,
            @ForAll("holdings") Holding unlicensed) {
        List<PolicyInput> more = new ArrayList<>(policies);
        more.add(new PolicyInput(unlicensed, false));

        assertThat(EffectivePermissions.computeAll(profile, more, grants))
                .isEqualTo(EffectivePermissions.computeAll(profile, policies, grants));
    }

    @Property
    void aProfileWithoutItsLicenceContributesNothing(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<PolicyInput> policies, @ForAll("grantLists") List<Holding> grants) {
        ProfileInput unlicensed = new ProfileInput(profile.fullAccess(), profile.abilities(), profile.data(), false);

        assertThat(EffectivePermissions.computeAll(unlicensed, policies, grants))
                .isEqualTo(EffectivePermissions.computeAll(ProfileInput.none(), policies, grants));
    }

    @Property
    void aLicensedProfileWithFullAccessAllowsEverythingOnEveryObjectAndField(
            @ForAll("policyLists") List<PolicyInput> policies, @ForAll("grantLists") List<Holding> grants) {
        Effective result = EffectivePermissions.computeAll(
                new ProfileInput(true, Set.of(), DataAccess.none(), true), policies, grants);

        assertThat(result.data().everything()).isTrue();
        assertThat(result.abilities()).containsExactlyInAnyOrderElementsOf(Ability.all());
    }

    @Property
    void givingTheSamePolicyTwiceChangesNothing(@ForAll("profiles") ProfileInput profile,
            @ForAll("policyLists") List<PolicyInput> policies, @ForAll("grantLists") List<Holding> grants) {
        List<PolicyInput> twice = new ArrayList<>(policies);
        twice.addAll(policies);

        assertThat(EffectivePermissions.computeAll(profile, twice, grants))
                .isEqualTo(EffectivePermissions.computeAll(profile, policies, grants));
    }

    @Property
    void nothingGivesNothing(@ForAll("policyLists") List<PolicyInput> policies) {
        List<PolicyInput> unlicensed = policies.stream().map(policy -> new PolicyInput(policy.holding(), false))
                .toList();

        Effective result = EffectivePermissions.computeAll(ProfileInput.none(), unlicensed, List.of());

        assertThat(result.abilities()).isEmpty();
        assertThat(result.data().isEmpty()).isTrue();
    }

    @Property
    void aUnionCoversEachPartAndCoveringIsReflexive(@ForAll("data") DataAccess left,
            @ForAll("data") DataAccess right) {
        DataAccess both = left.union(right);

        assertThat(both.covers(left)).isTrue();
        assertThat(both.covers(right)).isTrue();
        assertThat(left.covers(left)).isTrue();
        assertThat(left.closed().covers(left)).isTrue();
        assertThat(left.covers(left.closed())).as("writing the implied actions out changes no answer").isTrue();
    }
}
