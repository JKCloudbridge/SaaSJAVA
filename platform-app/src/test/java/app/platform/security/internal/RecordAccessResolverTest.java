package app.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.Admission;
import app.platform.security.RecordAccess;
import app.platform.security.ShareLevel;
import app.platformapi.QueryFilterNode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The rules of record-level security (ADR-0116), one rule at a time. */
class RecordAccessResolverTest {

    private static final UUID ME = id(1);
    private static final UUID GROUP = id(50);
    private static final QueryFilterNode CONDITION = new QueryFilterNode("EQ", null, "stage__c", List.of("Open"));

    private static UUID id(int number) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", number));
    }

    /** A member who may read the object with no flags, under the default, with nobody below and no rule. */
    private static RecordAccessResolver.Facts facts(SharingDefault defaultAccess, boolean hierarchy,
            Set<UUID> subordinates, List<RecordAccessResolver.Rule> rules) {
        return new RecordAccessResolver.Facts("Deal__c", true, false, false, defaultAccess, hierarchy, ME,
                Set.of(ME, GROUP), subordinates, rules, 20_000);
    }

    @Test
    void aMemberWhoMayNotReadTheObjectReachesNothingAtAll() {
        RecordAccess access = RecordAccessResolver.resolve(new RecordAccessResolver.Facts("Deal__c", false, true,
                true, SharingDefault.PUBLIC_READ_WRITE, true, ME, Set.of(ME), Set.of(), List.of(), 10));

        assertThat(access).isEqualTo(RecordAccess.nothing("Deal__c"));
    }

    @Test
    void modifyAllReachesEveryRecordInEveryWayWhateverTheDefault() {
        RecordAccess access = RecordAccessResolver.resolve(new RecordAccessResolver.Facts("Deal__c", true, false,
                true, SharingDefault.PRIVATE, false, ME, Set.of(ME), Set.of(), List.of(), 10));

        assertThat(access.unrestricted()).isTrue();
    }

    @Test
    void thePublicReadWriteDefaultKeepsTheBehaviourOfBeforeSprint17() {
        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PUBLIC_READ_WRITE, true,
                Set.of(id(2)), List.of()));

        assertThat(access.unrestricted()).isTrue();
    }

    @Test
    void publicReadLetsEveryoneReadAndOnlyTheOwnerChangeAndDelete() {
        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PUBLIC_READ, true, Set.of(id(2)),
                List.of()));

        assertThat(access.read()).isInstanceOf(Admission.Everything.class);
        assertThat(access.edit()).isEqualTo(Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ME)),
                new Admission.SharedWith(Set.of(ME, GROUP), ShareLevel.READ_WRITE))));
        assertThat(access.delete()).isEqualTo(new Admission.OwnedBy(Set.of(ME)));
    }

    @Test
    void privateGivesTheOwnRecordsThePeopleBelowAndWhatIsSharedAndNothingElse() {
        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PRIVATE, true,
                Set.of(id(2), id(3)), List.of()));

        assertThat(access.read()).isEqualTo(Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ME, id(2), id(3))),
                new Admission.SharedWith(Set.of(ME, GROUP), ShareLevel.READ))));
    }

    @Test
    void theHierarchyGivesTheRightToReadAndNeverToChangeOrDelete() {
        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PRIVATE, true, Set.of(id(2)),
                List.of()));

        assertThat(access.edit()).isEqualTo(Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ME)),
                new Admission.SharedWith(Set.of(ME, GROUP), ShareLevel.READ_WRITE))));
        assertThat(access.delete()).isEqualTo(new Admission.OwnedBy(Set.of(ME)));
    }

    @Test
    void withTheHierarchySwitchedOffAManagerReadsOnlyTheirOwn() {
        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PRIVATE, false, Set.of(id(2)),
                List.of()));

        assertThat(access.read()).isEqualTo(Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ME)),
                new Admission.SharedWith(Set.of(ME, GROUP), ShareLevel.READ))));
    }

    @Test
    void viewAllReadsEveryRecordButChangesNothingMoreThanTheOtherMechanismsGive() {
        RecordAccess access = RecordAccessResolver.resolve(new RecordAccessResolver.Facts("Deal__c", true, true,
                false, SharingDefault.PRIVATE, true, ME, Set.of(ME), Set.of(), List.of(), 10));

        assertThat(access.read()).isInstanceOf(Admission.Everything.class);
        assertThat(access.edit()).isNotInstanceOf(Admission.Everything.class);
        assertThat(access.delete()).isEqualTo(new Admission.OwnedBy(Set.of(ME)));
    }

    @Test
    void aRuleAtTheReadLevelGivesReadAndAReadWriteRuleGivesBothButNeverDelete() {
        RecordAccessResolver.Rule readRule = RecordAccessResolver.Rule.ofOwners(id(100), ShareLevel.READ,
                Set.of(id(7)));
        RecordAccessResolver.Rule writeRule = RecordAccessResolver.Rule.ofOwners(id(101), ShareLevel.READ_WRITE,
                Set.of(id(8)));

        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PRIVATE, false, Set.of(),
                List.of(readRule, writeRule)));

        assertThat(access.read()).isEqualTo(Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ME, id(7), id(8))),
                new Admission.SharedWith(Set.of(ME, GROUP), ShareLevel.READ))));
        assertThat(access.edit()).isEqualTo(Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ME, id(8))),
                new Admission.SharedWith(Set.of(ME, GROUP), ShareLevel.READ_WRITE))));
        assertThat(access.delete()).isEqualTo(new Admission.OwnedBy(Set.of(ME)));
    }

    @Test
    void aRuleByConditionIsCarriedAsTheConditionAtItsLevel() {
        RecordAccessResolver.Rule read = RecordAccessResolver.Rule.ofCriterion(id(100), ShareLevel.READ, CONDITION);
        RecordAccessResolver.Rule write = RecordAccessResolver.Rule.ofCriterion(id(101), ShareLevel.READ_WRITE,
                CONDITION);

        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PRIVATE, false, Set.of(),
                List.of(read, write)));

        assertThat(((Admission.AnyOf) access.read()).parts()).contains(new Admission.Meets(id(100), CONDITION),
                new Admission.Meets(id(101), CONDITION));
        assertThat(((Admission.AnyOf) access.edit()).parts()).contains(new Admission.Meets(id(101), CONDITION))
                .doesNotContain(new Admission.Meets(id(100), CONDITION));
        assertThat(access.delete()).isEqualTo(new Admission.OwnedBy(Set.of(ME)));
    }

    @Test
    void theEditAdmissionIsContainedInTheReadAdmissionAndTheDeleteAdmissionInTheEditAdmission() {
        RecordAccessResolver.Rule write = RecordAccessResolver.Rule.ofOwners(id(101), ShareLevel.READ_WRITE,
                Set.of(id(8)));

        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PRIVATE, true, Set.of(id(2)),
                List.of(write)));

        Set<UUID> readOwners = ownersOf(access.read());
        assertThat(readOwners).containsAll(ownersOf(access.edit()));
        assertThat(ownersOf(access.edit())).containsAll(ownersOf(access.delete()));
    }

    @Test
    void aSetOfOwnersLargerThanTheBoundIsCutAndSaysSoAndKeepsTheMemberHimself() {
        Set<UUID> many = IntStream.range(100, 300).mapToObj(RecordAccessResolverTest::id)
                .collect(Collectors.toCollection(TreeSet::new));

        RecordAccess access = RecordAccessResolver.resolve(new RecordAccessResolver.Facts("Deal__c", true, false,
                false, SharingDefault.PRIVATE, true, ME, Set.of(ME), many, List.of(), 50));

        assertThat(access.truncated()).isTrue();
        assertThat(ownersOf(access.read())).hasSize(50).contains(ME);
        assertThat(ownersOf(access.read())).as("a cut set only ever has fewer owners, never others")
                .isSubsetOf(withMe(many, ME));
    }

    @Test
    void aSetOfOwnersWithinTheBoundIsNotCut() {
        RecordAccess access = RecordAccessResolver.resolve(facts(SharingDefault.PRIVATE, true,
                Set.of(id(2), id(3)), List.of()));

        assertThat(access.truncated()).isFalse();
    }

    @Test
    void theBoundKeepsTheMemberFirstThenTheLowestIdentifiers() {
        Set<UUID> owners = Set.of(id(9), id(1), id(5), id(7));

        RecordAccessResolver.Bounded bounded = RecordAccessResolver.bounded(owners, id(9), 2);

        assertThat(bounded.cut()).isTrue();
        assertThat(bounded.owners()).containsExactlyInAnyOrder(id(9), id(1));
    }

    private static Set<UUID> withMe(Set<UUID> others, UUID extra) {
        Set<UUID> all = new TreeSet<>(others);
        all.add(extra);
        return all;
    }

    /** The owners an admission names (its owner parts), for comparing what contains what. */
    private static Set<UUID> ownersOf(Admission admission) {
        return switch (admission) {
            case Admission.OwnedBy owned -> owned.owners();
            case Admission.AnyOf anyOf -> anyOf.parts().stream().flatMap(part -> ownersOf(part).stream())
                    .collect(Collectors.toSet());
            case Admission.Everything everything -> Set.of();
            case Admission.Nothing nothing -> Set.of();
            case Admission.SharedWith shared -> Set.of();
            case Admission.Meets meets -> Set.of();
        };
    }
}
