package app.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.Admission;
import app.platform.security.Grantee;
import app.platform.security.RecordAccess;
import app.platform.security.ShareLevel;
import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Record-level security over generated configurations (ADR-0118): for any default, flags, hierarchy, rules and shares,
 * and any record, the description the resolver gives admits exactly the records the rules say. The rules are written
 * out again here in plain terms, independently of the resolver, and the description is read by a small interpreter
 * that is the specification of what an {@link Admission} means; the integration tests then hold the statements of the
 * data module to the same interpreter.
 *
 * <p>Shares to roles (ADR-0178, ADR-0185) are judged against a fixed role tree written out in this class and walked
 * here, not by the resolver's own chain:
 * <pre>
 *   TOP
 *    +-- MIDDLE
 *    |    +-- LEAF
 *    |    +-- OTHER_LEAF
 *    +-- SIDE
 * </pre>
 */
class RecordAccessPropertyTest {

    private static final List<UUID> PEOPLE = ids(1, 8);
    private static final UUID ME = PEOPLE.get(0);
    private static final List<UUID> GROUPS = ids(50, 51);

    private static final UUID TOP = id(60);
    private static final UUID MIDDLE = id(61);
    private static final UUID LEAF = id(62);
    private static final UUID OTHER_LEAF = id(63);
    private static final UUID SIDE = id(64);
    private static final List<UUID> ROLES = List.of(TOP, MIDDLE, LEAF, OTHER_LEAF, SIDE);
    private static final Map<UUID, UUID> PARENT = Map.of(MIDDLE, TOP, LEAF, MIDDLE, OTHER_LEAF, MIDDLE, SIDE, TOP);

    private static UUID id(int number) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", number));
    }

    private static List<UUID> ids(int from, int to) {
        List<UUID> ids = new ArrayList<>();
        for (int number = from; number <= to; number++) {
            ids.add(id(number));
        }
        return ids;
    }

    /** The role and every role above it, walked up the tree above. */
    private static Set<UUID> chainOf(UUID role) {
        Set<UUID> chain = new LinkedHashSet<>();
        for (UUID cursor = role; cursor != null; cursor = PARENT.get(cursor)) {
            chain.add(cursor);
        }
        return chain;
    }

    /** One share as the table holds it: its kind and its level; the grantee is the key of the record's map. */
    private record Share(Grantee.Kind kind, ShareLevel level) {
    }

    /** A record, as far as record-level security is concerned. */
    private record Rec(UUID owner, Map<UUID, Share> shares, Set<UUID> met) {
    }

    /** What the generator makes: the facts about the member and the object, and a few records. */
    private record Scenario(RecordAccessResolver.Facts facts, List<Rec> records) {
    }

    private static RecordAccessResolver.Facts facts(boolean canRead, boolean viewAll, boolean modifyAll,
            SharingDefault defaultAccess, boolean hierarchy, Set<UUID> groups, UUID role, Set<UUID> subordinates,
            List<RecordAccessResolver.Rule> rules) {
        return new RecordAccessResolver.Facts("Deal__c", canRead, viewAll, modifyAll, defaultAccess, hierarchy, ME,
                groups, role, role == null ? Set.of() : chainOf(role), subordinates, rules, 1000);
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<SharingDefault> defaults = Arbitraries.of(SharingDefault.class);
        Arbitrary<Set<UUID>> someone = Arbitraries.of(PEOPLE).set().ofMaxSize(5);
        Arbitrary<Set<UUID>> groups = Arbitraries.of(GROUPS).set().ofMaxSize(2);
        Arbitrary<UUID> role = Arbitraries.of(ROLES).injectNull(0.2);
        Arbitrary<List<RecordAccessResolver.Rule>> rules = rule().list().ofMaxSize(3);
        Arbitrary<List<Rec>> records = record().list().ofMinSize(1).ofMaxSize(8);
        Arbitrary<boolean[]> flags = Combinators.combine(Arbitraries.of(true, false), Arbitraries.of(true, false),
                Arbitraries.of(true, false), Arbitraries.of(true, false))
                .as((canRead, viewAll, modifyAll, hierarchy) -> new boolean[] {canRead, viewAll, modifyAll, hierarchy});
        return Combinators.combine(defaults, flags, someone, groups, role, rules, records)
                .as((def, flag, subordinates, memberGroups, memberRole, ruleList, recordList) -> {
                    List<RecordAccessResolver.Rule> numbered = new ArrayList<>();
                    for (int index = 0; index < ruleList.size(); index++) {
                        RecordAccessResolver.Rule rule = ruleList.get(index);
                        numbered.add(new RecordAccessResolver.Rule(id(100 + index), rule.level(), rule.owners(),
                                rule.criterion()));
                    }
                    Set<UUID> ruleIds = new HashSet<>();
                    numbered.forEach(rule -> ruleIds.add(rule.id()));
                    List<Rec> fixed = recordList.stream().map(rec -> {
                        Set<UUID> met = new HashSet<>(rec.met());
                        met.retainAll(ruleIds);
                        return new Rec(rec.owner(), rec.shares(), met);
                    }).toList();
                    return new Scenario(facts(flag[0], flag[1], flag[2], def, flag[3], memberGroups, memberRole,
                            subordinates, numbered), fixed);
                });
    }

    private static Arbitrary<RecordAccessResolver.Rule> rule() {
        Arbitrary<ShareLevel> level = Arbitraries.of(ShareLevel.class);
        Arbitrary<Set<UUID>> owners = Arbitraries.of(PEOPLE).set().ofMaxSize(4);
        QueryFilterNode condition = new QueryFilterNode("EQ", null, "stage__c", List.of("Open"));
        return Combinators.combine(level, owners, Arbitraries.of(true, false)).as((lvl, who, byCondition) ->
                byCondition ? RecordAccessResolver.Rule.ofCriterion(UUID.randomUUID(), lvl, condition)
                        : RecordAccessResolver.Rule.ofOwners(UUID.randomUUID(), lvl, who));
    }

    /** One share: a user, a group, a role alone or a role with those below, at a level. */
    private static Arbitrary<Map.Entry<UUID, Share>> oneShare() {
        Arbitrary<ShareLevel> level = Arbitraries.of(ShareLevel.class);
        Arbitrary<Map.Entry<UUID, Grantee.Kind>> grantee = Arbitraries.oneOf(
                Arbitraries.of(PEOPLE).map(person -> Map.entry(person, Grantee.Kind.USER)),
                Arbitraries.of(GROUPS).map(group -> Map.entry(group, Grantee.Kind.GROUP)),
                Arbitraries.of(ROLES).map(role -> Map.entry(role, Grantee.Kind.ROLE)),
                Arbitraries.of(ROLES).map(role -> Map.entry(role, Grantee.Kind.ROLE_AND_SUBORDINATES)));
        return Combinators.combine(grantee, level).as((who, lvl) -> Map.entry(who.getKey(),
                new Share(who.getValue(), lvl)));
    }

    private static Arbitrary<Rec> record() {
        Arbitrary<Map<UUID, Share>> shares = oneShare().list().ofMaxSize(4).map(list -> {
            // The table keeps one share of a record for each grantee: the last one made stands.
            Map<UUID, Share> byGrantee = new HashMap<>();
            list.forEach(entry -> byGrantee.put(entry.getKey(), entry.getValue()));
            return byGrantee;
        });
        Arbitrary<Set<UUID>> met = Arbitraries.of(100, 101, 102).map(RecordAccessPropertyTest::id).set()
                .ofMaxSize(3);
        return Combinators.combine(Arbitraries.of(PEOPLE), shares, met).as(Rec::new);
    }

    // ---- what an admission means (the specification the statements are held to) ----

    private static boolean admits(Admission admission, Rec record) {
        return switch (admission) {
            case Admission.Everything everything -> true;
            case Admission.Nothing nothing -> false;
            case Admission.AnyOf anyOf -> anyOf.parts().stream().anyMatch(part -> admits(part, record));
            case Admission.OwnedBy owned -> owned.owners().contains(record.owner());
            case Admission.SharedWith shared -> record.shares().entrySet().stream().anyMatch(share ->
                    shared.grantees().contains(new Grantee(share.getValue().kind(), share.getKey()))
                            && share.getValue().level().covers(shared.atLeast()));
            case Admission.Meets meets -> record.met().contains(meets.rule());
        };
    }

    // ---- the rules, written out again in plain terms ----

    /**
     * Whether the member is the one a share addresses, in the words of the rule: a user share is for that user; a group
     * share is for the members of the group; a role share is for the members whose own role it is; a share to a role
     * and the roles below it is for the members of that role and of every role below it.
     */
    private static boolean addressesMember(RecordAccessResolver.Facts facts, UUID grantee, Grantee.Kind kind) {
        return switch (kind) {
            case USER -> grantee.equals(facts.user());
            case GROUP -> facts.groups().contains(grantee);
            case ROLE -> grantee.equals(facts.role());
            case ROLE_AND_SUBORDINATES -> isSameOrBelow(facts.role(), grantee);
        };
    }

    /** Whether the member's role is the role or is somewhere below it in the tree; a member with no role is not. */
    private static boolean isSameOrBelow(UUID memberRole, UUID role) {
        for (UUID cursor = memberRole; cursor != null; cursor = PARENT.get(cursor)) {
            if (cursor.equals(role)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharedAt(Rec record, RecordAccessResolver.Facts facts, ShareLevel level) {
        return record.shares().entrySet().stream().anyMatch(share ->
                addressesMember(facts, share.getKey(), share.getValue().kind())
                        && share.getValue().level().covers(level));
    }

    private static boolean ruleGives(RecordAccessResolver.Facts facts, Rec record, ShareLevel level) {
        return facts.rules().stream().anyMatch(rule -> rule.level().covers(level)
                && (rule.owners().contains(record.owner()) || (rule.criterion() != null
                        && record.met().contains(rule.id()))));
    }

    private static boolean mayRead(RecordAccessResolver.Facts facts, Rec record) {
        if (!facts.canRead()) {
            return false;
        }
        if (facts.modifyAll() || facts.viewAll() || facts.defaultAccess() != SharingDefault.PRIVATE) {
            return true;
        }
        return record.owner().equals(facts.user())
                || (facts.hierarchy() && facts.subordinates().contains(record.owner()))
                || ruleGives(facts, record, ShareLevel.READ) || sharedAt(record, facts, ShareLevel.READ);
    }

    private static boolean mayEdit(RecordAccessResolver.Facts facts, Rec record) {
        if (!facts.canRead()) {
            return false;
        }
        return facts.modifyAll() || facts.defaultAccess() == SharingDefault.PUBLIC_READ_WRITE
                || record.owner().equals(facts.user()) || ruleGives(facts, record, ShareLevel.READ_WRITE)
                || sharedAt(record, facts, ShareLevel.READ_WRITE);
    }

    private static boolean mayDelete(RecordAccessResolver.Facts facts, Rec record) {
        if (!facts.canRead()) {
            return false;
        }
        return facts.modifyAll() || facts.defaultAccess() == SharingDefault.PUBLIC_READ_WRITE
                || record.owner().equals(facts.user());
    }

    @Property
    void theDescriptionAdmitsExactlyTheRecordsTheRulesSay(@ForAll("scenarios") Scenario scenario) {
        RecordAccess access = RecordAccessResolver.resolve(scenario.facts());

        for (Rec record : scenario.records()) {
            assertThat(admits(access.read(), record)).as("read").isEqualTo(mayRead(scenario.facts(), record));
            assertThat(admits(access.edit(), record)).as("edit").isEqualTo(mayEdit(scenario.facts(), record));
            assertThat(admits(access.delete(), record)).as("delete").isEqualTo(mayDelete(scenario.facts(), record));
        }
        assertThat(access.truncated()).isFalse();
    }

    @Property
    void whatMayBeDeletedMayBeChangedAndWhatMayBeChangedMayBeRead(@ForAll("scenarios") Scenario scenario) {
        RecordAccess access = RecordAccessResolver.resolve(scenario.facts());

        for (Rec record : scenario.records()) {
            if (admits(access.delete(), record)) {
                assertThat(admits(access.edit(), record)).isTrue();
            }
            if (admits(access.edit(), record)) {
                assertThat(admits(access.read(), record)).isTrue();
            }
        }
    }

    @Property
    void aMemberWhoOwnsARecordAndMayReadTheObjectMayDoEverythingWithIt(@ForAll("scenarios") Scenario scenario) {
        RecordAccess access = RecordAccessResolver.resolve(scenario.facts());
        Rec own = new Rec(ME, Map.of(), Set.of());

        assertThat(admits(access.read(), own)).isEqualTo(scenario.facts().canRead());
        assertThat(admits(access.edit(), own)).isEqualTo(scenario.facts().canRead());
        assertThat(admits(access.delete(), own)).isEqualTo(scenario.facts().canRead());
    }

    @Property
    void noRuleAndNoShareEverGivesTheRightToDeleteARecordOfSomeoneElse(@ForAll("scenarios") Scenario scenario) {
        RecordAccess access = RecordAccessResolver.resolve(scenario.facts());
        Map<UUID, Share> everyone = new HashMap<>();
        PEOPLE.forEach(person -> everyone.put(person, new Share(Grantee.Kind.USER, ShareLevel.READ_WRITE)));
        GROUPS.forEach(group -> everyone.put(group, new Share(Grantee.Kind.GROUP, ShareLevel.READ_WRITE)));
        ROLES.forEach(role -> everyone.put(role, new Share(Grantee.Kind.ROLE_AND_SUBORDINATES,
                ShareLevel.READ_WRITE)));
        Rec theirs = new Rec(PEOPLE.get(7), everyone, Set.of(id(100)));
        boolean publicWrite = scenario.facts().defaultAccess() == SharingDefault.PUBLIC_READ_WRITE;
        boolean allowed = scenario.facts().canRead() && (scenario.facts().modifyAll() || publicWrite
                || ME.equals(theirs.owner()));

        assertThat(admits(access.delete(), theirs)).isEqualTo(allowed);
    }

    // ---- the sentences of the rule for roles, with the tree above ----

    /** A Private object, a member with this role, one record of someone else shared with a role. */
    private static boolean reads(UUID memberRole, Grantee.Kind kind, UUID sharedRole) {
        RecordAccessResolver.Facts facts = facts(true, false, false, SharingDefault.PRIVATE, false, Set.of(),
                memberRole, Set.of(), List.of());
        Rec record = new Rec(PEOPLE.get(7), Map.of(sharedRole, new Share(kind, ShareLevel.READ)), Set.of());
        return admits(RecordAccessResolver.resolve(facts).read(), record);
    }

    @Test
    void aMemberInARoleReadsARecordSharedWithThatRoleAndAMemberBelowItDoesNot() {
        assertThat(reads(MIDDLE, Grantee.Kind.ROLE, MIDDLE)).isTrue();
        assertThat(reads(LEAF, Grantee.Kind.ROLE, MIDDLE)).as("below").isFalse();
        assertThat(reads(TOP, Grantee.Kind.ROLE, MIDDLE)).as("above: only through the hierarchy, as today").isFalse();
        assertThat(reads(SIDE, Grantee.Kind.ROLE, MIDDLE)).as("beside").isFalse();
    }

    @Test
    void aShareToARoleAndTheRolesBelowItIsReadByThatRoleAndEveryRoleBelowAndByNobodyElse() {
        assertThat(reads(MIDDLE, Grantee.Kind.ROLE_AND_SUBORDINATES, MIDDLE)).isTrue();
        assertThat(reads(LEAF, Grantee.Kind.ROLE_AND_SUBORDINATES, MIDDLE)).isTrue();
        assertThat(reads(OTHER_LEAF, Grantee.Kind.ROLE_AND_SUBORDINATES, MIDDLE)).isTrue();
        assertThat(reads(TOP, Grantee.Kind.ROLE_AND_SUBORDINATES, MIDDLE)).as("above").isFalse();
        assertThat(reads(SIDE, Grantee.Kind.ROLE_AND_SUBORDINATES, MIDDLE)).as("beside").isFalse();
        assertThat(reads(LEAF, Grantee.Kind.ROLE_AND_SUBORDINATES, OTHER_LEAF)).as("a sibling").isFalse();
        assertThat(reads(LEAF, Grantee.Kind.ROLE_AND_SUBORDINATES, TOP)).as("two levels up").isTrue();
    }

    @Test
    void aMemberWithNoRoleMatchesNoRoleShare() {
        for (UUID role : ROLES) {
            assertThat(reads(null, Grantee.Kind.ROLE, role)).isFalse();
            assertThat(reads(null, Grantee.Kind.ROLE_AND_SUBORDINATES, role)).isFalse();
        }
    }

    @Test
    void theSameIdentifierOfAnotherKindIsNotTheShare() {
        // A share to a group never reaches the member of a role with the same identifier, nor the reverse.
        RecordAccessResolver.Facts facts = facts(true, false, false, SharingDefault.PRIVATE, false, Set.of(), LEAF,
                Set.of(), List.of());
        Rec toGroup = new Rec(PEOPLE.get(7), Map.of(LEAF, new Share(Grantee.Kind.GROUP, ShareLevel.READ)), Set.of());
        Rec toUser = new Rec(PEOPLE.get(7), Map.of(LEAF, new Share(Grantee.Kind.USER, ShareLevel.READ)), Set.of());

        assertThat(admits(RecordAccessResolver.resolve(facts).read(), toGroup)).isFalse();
        assertThat(admits(RecordAccessResolver.resolve(facts).read(), toUser)).isFalse();
    }
}
