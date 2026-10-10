package app.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import app.platform.security.Ability;
import app.platform.security.DataAccess;
import app.platform.security.Decision;
import app.platform.security.Decision.Reason;
import app.platform.security.EffectivePermissions;
import app.platform.security.EffectivePermissions.Holding;
import app.platform.security.EffectivePermissions.PolicyInput;
import app.platform.security.EffectivePermissions.ProfileInput;
import app.platform.security.FieldAction;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.ObjectCatalog;
import app.platform.security.Permissions;
import app.platform.security.RecordRef;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The decision matrix (ADR-0050): the decision API answers, for every combination of what a member can hold, exactly
 * what
 * the documented algorithm says. Table-driven: every combination of what the profile, an access policy, a group's
 * access
 * policy and an individual grant give, whether the profile and the policies are licensed, every object action and
 * field action, and the cases that must never be told apart (an unknown member, a deactivated member, a member of
 * another organization, a platform person, an unknown object, an unknown field). Each case is computed by the real
 * calculator and the real decision code; the expected answer is written out again here from the documented rules, not
 * taken from the code. The tests were checked to fail when a rule is changed on purpose: see {@code Sprint 8.md}.
 */
class DecisionMatrixTest {

    private static final UUID MEMBER = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final String OBJECT = "object-a";
    private static final String FIELD = "field-a";
    private static final String FIELD_KEY = OBJECT + "." + FIELD;

    private static final List<Set<ObjectAction>> OBJECT_CHOICES = List.of(
            EnumSet.noneOf(ObjectAction.class), EnumSet.of(ObjectAction.READ), EnumSet.of(ObjectAction.UPDATE),
            EnumSet.of(ObjectAction.MODIFY_ALL));
    private static final List<Set<FieldAction>> FIELD_CHOICES = List.of(
            EnumSet.noneOf(FieldAction.class), EnumSet.of(FieldAction.READ), EnumSet.of(FieldAction.EDIT));

    private static final TenantContexts CONTEXTS = mock(TenantContexts.class);

    private static final ObjectCatalog CATALOGUE = new FixedObjectCatalog(List.of(
            new ObjectCatalog.ObjectInfo(OBJECT, "Object A", List.of(new ObjectCatalog.FieldInfo(FIELD, "Field A"),
                    new ObjectCatalog.FieldInfo("field-b", "Field B"))),
            new ObjectCatalog.ObjectInfo("object-b", "Object B", List.of(new ObjectCatalog.FieldInfo(FIELD, "F")))));

    /** What a member's data allows, by membership; a member who is not in it holds nothing. */
    private record FixedPermissions(Map<UUID, DataAccess> byMember) implements Permissions {

        FixedPermissions {
            byMember = Map.copyOf(byMember);
        }

        @Override
        public Set<Ability> effective(UUID membershipId) {
            return Set.of();
        }

        @Override
        public DataAccess data(UUID membershipId) {
            return byMember.getOrDefault(membershipId, DataAccess.none());
        }
    }

    private static DefaultDecisions decisionsFor(DataAccess dataOfMember) {
        TransactionTemplate direct = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        AccessWork work = new AccessWork(CONTEXTS, direct);
        return new DefaultDecisions(new FixedPermissions(Map.of(MEMBER, dataOfMember)), CATALOGUE, work);
    }

    // ---- the documented rules, written out again ----

    private static boolean implies(ObjectAction held, ObjectAction asked) {
        return held == asked || switch (held) {
            case MODIFY_ALL -> true;
            case CREATE, UPDATE, DELETE, VIEW_ALL -> asked == ObjectAction.READ;
            case READ -> false;
        };
    }

    private static boolean objectAllowed(List<Set<ObjectAction>> counting, ObjectAction asked) {
        return counting.stream().flatMap(Set::stream).anyMatch(held -> implies(held, asked));
    }

    private static boolean fieldAllowed(List<Set<FieldAction>> counting, FieldAction asked) {
        return counting.stream().flatMap(Set::stream)
                .anyMatch(held -> held == asked || (held == FieldAction.EDIT && asked == FieldAction.READ));
    }

    private static DataAccess objects(Set<ObjectAction> actions) {
        return actions.isEmpty() ? DataAccess.none() : DataAccess.of(Map.of(OBJECT, actions), Map.of());
    }

    private static DataAccess fields(Set<FieldAction> actions) {
        return actions.isEmpty() ? DataAccess.none() : DataAccess.of(Map.of(), Map.of(FIELD_KEY, actions));
    }

    private static DataAccess calculated(DataAccess profile, DataAccess policy, DataAccess group, DataAccess grant,
            boolean profileLicensed, boolean policiesLicensed) {
        return EffectivePermissions.computeAll(new ProfileInput(false, Set.of(), profile, profileLicensed),
                List.of(new PolicyInput(new Holding(Set.of(), policy), policiesLicensed),
                        new PolicyInput(new Holding(Set.of(), group), policiesLicensed)),
                List.of(new Holding(Set.of(), grant))).data();
    }

    // ---- matrix 1: object permissions from every combination of sources ----

    static Stream<Arguments> objectCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Set<ObjectAction> profile : OBJECT_CHOICES) {
            for (Set<ObjectAction> policy : OBJECT_CHOICES) {
                for (Set<ObjectAction> group : OBJECT_CHOICES) {
                    for (Set<ObjectAction> grant : OBJECT_CHOICES) {
                        for (boolean profileLicensed : List.of(true, false)) {
                            for (boolean policiesLicensed : List.of(true, false)) {
                                cases.add(Arguments.of(profile, policy, group, grant, profileLicensed,
                                        policiesLicensed));
                            }
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] profile={0} policy={1} group={2} grant={3} licensed={4}/{5}")
    @MethodSource("objectCases")
    void everyCombinationOfSourcesGivesExactlyWhatTheRulesSay(Set<ObjectAction> profile, Set<ObjectAction> policy,
            Set<ObjectAction> group, Set<ObjectAction> grant, boolean profileLicensed, boolean policiesLicensed) {
        DefaultDecisions decisions = decisionsFor(calculated(objects(profile), objects(policy), objects(group),
                objects(grant), profileLicensed, policiesLicensed));
        List<Set<ObjectAction>> counting = new ArrayList<>();
        if (profileLicensed) {
            counting.add(profile);
        }
        if (policiesLicensed) {
            counting.add(policy);
            counting.add(group);
        }
        counting.add(grant);

        for (ObjectAction action : ObjectAction.values()) {
            Decision decision = decisions.can(MEMBER, OBJECT, action);
            assertThat(decision.allowed()).as(action.key()).isEqualTo(objectAllowed(counting, action));
            assertThat(decision.reason()).isEqualTo(decision.allowed() ? Reason.ALLOWED : Reason.OBJECT_NOT_ALLOWED);
            assertThat(decisions.can(MEMBER, OBJECT, action, new RecordRef(UUID.randomUUID())))
                    .as("a record changes nothing until Sprint 17").isEqualTo(decision);
            assertThat(decisions.can(MEMBER, "object-b", action).allowed()).as("another object, nothing given")
                    .isFalse();
        }
    }

    // ---- matrix 2: field permissions from every combination of sources and of what the object allows ----

    static Stream<Arguments> fieldCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Set<FieldAction> profile : FIELD_CHOICES) {
            for (Set<FieldAction> policy : FIELD_CHOICES) {
                for (Set<FieldAction> group : FIELD_CHOICES) {
                    for (Set<FieldAction> grant : FIELD_CHOICES) {
                        for (Set<ObjectAction> onObject : OBJECT_CHOICES) {
                            for (boolean licensed : List.of(true, false)) {
                                cases.add(Arguments.of(profile, policy, group, grant, onObject, licensed));
                            }
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] profile={0} policy={1} group={2} grant={3} object={4} licensed={5}")
    @MethodSource("fieldCases")
    void aFieldNeedsItsOwnPermissionAndTheObjectsPermission(Set<FieldAction> profile, Set<FieldAction> policy,
            Set<FieldAction> group, Set<FieldAction> grant, Set<ObjectAction> onObject, boolean licensed) {
        // The object permission comes from the grant (always counts); the field permissions from every source, the
        // profile and the policies only while licensed.
        DataAccess data = EffectivePermissions.computeAll(
                new ProfileInput(false, Set.of(), fields(profile), licensed),
                List.of(new PolicyInput(new Holding(Set.of(), fields(policy)), licensed),
                        new PolicyInput(new Holding(Set.of(), fields(group)), licensed)),
                List.of(new Holding(Set.of(), objects(onObject).union(fields(grant))))).data();
        DefaultDecisions decisions = decisionsFor(data);
        List<Set<FieldAction>> counting = new ArrayList<>();
        if (licensed) {
            counting.add(profile);
            counting.add(policy);
            counting.add(group);
        }
        counting.add(grant);
        List<Set<ObjectAction>> onObjectOnly = List.of(onObject);
        boolean canRead = objectAllowed(onObjectOnly, ObjectAction.READ);
        boolean canWrite = objectAllowed(onObjectOnly, ObjectAction.CREATE)
                || objectAllowed(onObjectOnly, ObjectAction.UPDATE);

        Decision read = decisions.can(MEMBER, OBJECT, FIELD, FieldAction.READ);
        Decision edit = decisions.can(MEMBER, OBJECT, FIELD, FieldAction.EDIT);

        assertThat(read.allowed()).as("read").isEqualTo(canRead && fieldAllowed(counting, FieldAction.READ));
        assertThat(edit.allowed()).as("edit").isEqualTo(canWrite && fieldAllowed(counting, FieldAction.EDIT));
        assertThat(decisions.can(MEMBER, OBJECT, "field-b", FieldAction.READ).allowed())
                .as("a field nothing was given for").isFalse();
        ObjectAccess access = decisions.accessTo(MEMBER, OBJECT);
        assertThat(access.readableFields().contains(FIELD)).as("bulk read").isEqualTo(read.allowed());
        assertThat(access.editableFields().contains(FIELD)).as("bulk edit").isEqualTo(edit.allowed());
        assertThat(access.can(ObjectAction.READ)).isEqualTo(canRead);
    }

    // ---- matrix 3: what must never be told apart ----

    @Test
    void aMemberWhoDoesNotExistOrHoldsNothingGetsTheSameNoAsEveryoneElseWhoMayNotKnow() {
        DefaultDecisions decisions = decisionsFor(DataAccess.fullAccess());
        List<UUID> nobody = new ArrayList<>(List.of(STRANGER, UUID.randomUUID()));

        for (ObjectAction action : ObjectAction.values()) {
            for (UUID member : nobody) {
                assertThat(decisions.can(member, OBJECT, action).allowed())
                        .as("a member who is not in this organization, deactivated or unknown").isFalse();
                assertThat(decisions.can(member, "object-z", action).allowed()).isFalse();
            }
            assertThat(decisions.can(null, OBJECT, action).allowed()).as("a platform person has no membership")
                    .isFalse();
            assertThat(decisions.can(MEMBER, null, action).allowed()).isFalse();
        }
        assertThat(decisions.can(MEMBER, OBJECT, (ObjectAction) null).allowed()).isFalse();
        assertThat(decisions.can(STRANGER, OBJECT, FIELD, FieldAction.READ).allowed()).isFalse();
        assertThat(decisions.can(null, OBJECT, FIELD, FieldAction.READ).allowed()).isFalse();
        assertThat(decisions.accessTo(STRANGER, OBJECT)).isEqualTo(ObjectAccess.none(OBJECT));
        assertThat(decisions.accessToAll(STRANGER)).isEmpty();
        assertThat(decisions.accessToAll(null)).isEmpty();
    }

    @Test
    void anObjectOrFieldThatDoesNotExistIsRefusedEvenForFullAccess() {
        DefaultDecisions decisions = decisionsFor(DataAccess.fullAccess());

        for (ObjectAction action : ObjectAction.values()) {
            Decision unknown = decisions.can(MEMBER, "object-z", action);
            assertThat(unknown.allowed()).isFalse();
            assertThat(unknown.reason()).isEqualTo(Reason.UNKNOWN_OBJECT);
            assertThat(decisions.can(MEMBER, OBJECT, action).allowed()).as("the real one is allowed").isTrue();
        }
        for (FieldAction action : FieldAction.values()) {
            assertThat(decisions.can(MEMBER, "object-z", FIELD, action).reason()).isEqualTo(Reason.UNKNOWN_OBJECT);
            assertThat(decisions.can(MEMBER, OBJECT, "field-z", action).reason()).isEqualTo(Reason.UNKNOWN_FIELD);
            assertThat(decisions.can(MEMBER, "object-b", "field-b", action).reason())
                    .as("a field of another object").isEqualTo(Reason.UNKNOWN_FIELD);
            assertThat(decisions.can(MEMBER, OBJECT, FIELD, action).allowed()).isTrue();
        }
        assertThat(decisions.accessTo(MEMBER, "object-z")).isEqualTo(ObjectAccess.none("object-z"));
    }

    @Test
    void whatIsRefusedLooksTheSameWhetherTheObjectIsUnknownOrJustNotAllowed() {
        DefaultDecisions decisions = decisionsFor(objects(Set.of(ObjectAction.READ)));

        assertThat(decisions.can(MEMBER, "object-z", ObjectAction.READ).allowed())
                .isEqualTo(decisions.can(MEMBER, "object-b", ObjectAction.READ).allowed());
        assertThat(decisions.accessTo(MEMBER, "object-z").actions())
                .isEqualTo(decisions.accessTo(MEMBER, "object-b").actions());
        assertThat(decisions.accessTo(MEMBER, "object-z").readableFields())
                .isEqualTo(decisions.accessTo(MEMBER, "object-b").readableFields());
    }

    @Test
    void theBulkFormListsOnlyTheObjectsTheMemberMayUseWithTheirFields() {
        DataAccess data = objects(Set.of(ObjectAction.UPDATE)).union(fields(Set.of(FieldAction.EDIT)))
                .union(DataAccess.of(Map.of(), Map.of("object-a.field-b", EnumSet.of(FieldAction.READ))));
        DefaultDecisions decisions = decisionsFor(data);

        Map<String, ObjectAccess> all = decisions.accessToAll(MEMBER);

        assertThat(all.keySet()).containsExactly(OBJECT);
        ObjectAccess access = all.get(OBJECT);
        assertThat(access.actions()).containsExactlyInAnyOrder(ObjectAction.UPDATE, ObjectAction.READ);
        assertThat(access.readableFields()).containsExactlyInAnyOrder(FIELD, "field-b");
        assertThat(access.editableFields()).containsExactly(FIELD);
    }

    @Test
    void aFieldPermissionWithoutTheObjectPermissionAllowsNothing() {
        DefaultDecisions decisions = decisionsFor(fields(Set.of(FieldAction.EDIT)));

        assertThat(decisions.can(MEMBER, OBJECT, FIELD, FieldAction.READ).allowed()).isFalse();
        assertThat(decisions.can(MEMBER, OBJECT, FIELD, FieldAction.EDIT).allowed()).isFalse();
        assertThat(decisions.accessTo(MEMBER, OBJECT)).isEqualTo(ObjectAccess.none(OBJECT));
        assertThat(decisions.accessToAll(MEMBER)).isEmpty();
    }

    @Test
    void creatingOrUpdatingTheObjectIsEnoughToEditAPermittedField() {
        for (ObjectAction action : List.of(ObjectAction.CREATE, ObjectAction.UPDATE)) {
            DefaultDecisions decisions = decisionsFor(objects(Set.of(action)).union(fields(Set.of(FieldAction.EDIT))));

            assertThat(decisions.can(MEMBER, OBJECT, FIELD, FieldAction.EDIT).allowed()).as(action.key()).isTrue();
        }
        DefaultDecisions deleter = decisionsFor(objects(Set.of(ObjectAction.DELETE))
                .union(fields(Set.of(FieldAction.EDIT))));
        assertThat(deleter.can(MEMBER, OBJECT, FIELD, FieldAction.EDIT).allowed())
                .as("delete only reads, so it cannot edit").isFalse();
        assertThat(deleter.can(MEMBER, OBJECT, FIELD, FieldAction.READ).allowed()).isTrue();
    }
}
