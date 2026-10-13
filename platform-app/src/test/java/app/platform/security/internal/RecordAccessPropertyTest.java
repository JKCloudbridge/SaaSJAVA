package app.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.Admission;
import app.platform.security.RecordAccess;
import app.platform.security.ShareLevel;
import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

/**
 * Record-level security over generated configurations (ADR-0118): for any default, flags, hierarchy, rules and shares,
 * and any record, the description the resolver gives admits exactly the records the rules say. The rules are written
 * out again here in plain terms, independently of the resolver, and the description is read by a small interpreter
 * that is the specification of what an {@link Admission} means; the integration tests then hold the statements of the
 * data module to the same interpreter.
 */
class RecordAccessPropertyTest {

    private static final List<UUID> PEOPLE = people();
    private static final UUID ME = PEOPLE.get(0);

    private static List<UUID> people() {
        List<UUID> people = new ArrayList<>();
        for (int number = 1; number <= 8; number++) {
            people.add(UUID.fromString(String.format("00000000-0000-7000-8000-%012d", number)));
        }
        return people;
    }

    /** A record, as far as record-level security is concerned. */
    private record Rec(UUID owner, Map<UUID, ShareLevel> shares, Set<UUID> met) {
    }

    /** What the generator makes: the facts about the member and the object, and a few records. */
    private record Scenario(RecordAccessResolver.Facts facts, List<Rec> records) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<SharingDefault> defaults = Arbitraries.of(SharingDefault.class);
        Arbitrary<Set<UUID>> someone = Arbitraries.of(PEOPLE).set().ofMaxSize(5);
        Arbitrary<List<RecordAccessResolver.Rule>> rules = rule().list().ofMaxSize(3);
        Arbitrary<List<Rec>> records = record().list().ofMinSize(1).ofMaxSize(8);
        Arbitrary<boolean[]> flags = Combinators.combine(Arbitraries.of(true, false), Arbitraries.of(true, false),
                Arbitraries.of(true, false), Arbitraries.of(true, false))
                .as((canRead, viewAll, modifyAll, hierarchy) -> new boolean[] {canRead, viewAll, modifyAll, hierarchy});
        return Combinators.combine(defaults, flags, someone, someone, rules, records)
                .as((def, flag, subordinates, grantees, ruleList, recordList) -> {
                    boolean canRead = flag[0];
                    boolean viewAll = flag[1];
                    boolean modifyAll = flag[2];
                    boolean hierarchy = flag[3];
                    Set<UUID> allGrantees = new HashSet<>(grantees);
                    allGrantees.add(ME);
                    List<RecordAccessResolver.Rule> numbered = new ArrayList<>();
                    for (int index = 0; index < ruleList.size(); index++) {
                        RecordAccessResolver.Rule rule = ruleList.get(index);
                        UUID rid = UUID.fromString(String.format("00000000-0000-7000-8000-%012d", 100 + index));
                        numbered.add(new RecordAccessResolver.Rule(rid, rule.level(), rule.owners(), rule.criterion()));
                    }
                    Set<UUID> ruleIds = new HashSet<>();
                    numbered.forEach(rule -> ruleIds.add(rule.id()));
                    List<Rec> fixed = recordList.stream().map(rec -> {
                        Set<UUID> met = new HashSet<>(rec.met());
                        met.retainAll(ruleIds);
                        return new Rec(rec.owner(), rec.shares(), met);
                    }).toList();
                    return new Scenario(new RecordAccessResolver.Facts("Deal__c", canRead, viewAll, modifyAll, def,
                            hierarchy, ME, allGrantees, subordinates, numbered, 1000), fixed);
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

    private static Arbitrary<Rec> record() {
        Arbitrary<Map<UUID, ShareLevel>> shares = Arbitraries.maps(Arbitraries.of(PEOPLE),
                Arbitraries.of(ShareLevel.class)).ofMaxSize(3);
        Arbitrary<Set<UUID>> met = Arbitraries.of(100, 101, 102).map(number ->
                UUID.fromString(String.format("00000000-0000-7000-8000-%012d", number))).set().ofMaxSize(3);
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
                    shared.grantees().contains(share.getKey()) && share.getValue().covers(shared.atLeast()));
            case Admission.Meets meets -> record.met().contains(meets.rule());
        };
    }

    // ---- the rules, written out again in plain terms ----

    private static boolean sharedAt(Rec record, RecordAccessResolver.Facts facts, ShareLevel level) {
        return record.shares().entrySet().stream().anyMatch(share -> facts.grantees().contains(share.getKey())
                && share.getValue().covers(level));
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
        Map<UUID, ShareLevel> everyone = new HashMap<>();
        PEOPLE.forEach(person -> everyone.put(person, ShareLevel.READ_WRITE));
        Rec theirs = new Rec(PEOPLE.get(7), everyone, Set.of(UUID.fromString("00000000-0000-7000-8000-000000000100")));
        boolean publicWrite = scenario.facts().defaultAccess() == SharingDefault.PUBLIC_READ_WRITE;
        boolean allowed = scenario.facts().canRead() && (scenario.facts().modifyAll() || publicWrite
                || ME.equals(theirs.owner()));

        assertThat(admits(access.delete(), theirs)).isEqualTo(allowed);
    }
}
