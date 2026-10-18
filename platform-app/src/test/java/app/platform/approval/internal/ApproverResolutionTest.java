package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.approval.internal.ApproverResolution.Assignee;
import app.platform.approval.internal.ApproverResolution.Delegation;
import app.platform.approval.internal.ApproverResolution.Person;
import app.platform.approval.internal.ApproverResolution.Resolved;
import app.platform.approval.internal.ApproverResolution.Via;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Who is asked, as a pure function of facts (ADR-0146, ADR-0151): a person named twice is asked once, a person who is
 * not an
 * active licensed member or who is the submitter is left out with a stable reason, the fallback is tried only when
 * nobody is
 * left, a delegate is added beside the delegator and never instead, and a delegate's own delegation is not followed.
 */
class ApproverResolutionTest {

    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    private static Person ok(UUID id) {
        return new Person(id, true, true);
    }

    private static Function<UUID, Person> standing(Person... people) {
        Map<UUID, Person> known = new HashMap<>();
        for (Person person : people) {
            known.put(person.userId(), person);
        }
        return known::get;
    }

    private static List<UUID> asked(Resolved resolved) {
        return resolved.assignees().stream().map(Assignee::userId).toList();
    }

    @Test
    void aPersonNamedByTwoRulesIsAskedOnceInTheOrderTheyWereNamed() {
        Resolved resolved = ApproverResolution.resolve(List.of(List.of(ok(A), ok(B)), List.of(ok(B), ok(C))), List.of(),
                SUBMITTER, false, List.of(), standing());

        assertThat(asked(resolved)).containsExactly(A, B, C);
        assertThat(resolved.left()).isEmpty();
        assertThat(resolved.assignees()).allSatisfy(assignee -> assertThat(assignee.via()).isEqualTo(Via.DIRECT));
    }

    @Test
    void someoneWhoIsNotActiveOrNotLicensedIsLeftOutWithAReasonAndCounted() {
        Person inactive = new Person(A, false, true);
        Person unlicensed = new Person(B, true, false);

        Resolved resolved = ApproverResolution.resolve(List.of(List.of(inactive, unlicensed, ok(C))), List.of(),
                SUBMITTER,
                false, List.of(), standing());

        assertThat(asked(resolved)).containsExactly(C);
        assertThat(resolved.left()).containsEntry(ApproverResolution.INACTIVE, 1)
                .containsEntry(ApproverResolution.UNLICENSED, 1);
        assertThat(resolved.dropped()).extracting(ApproverResolution.Dropped::reason)
                .containsExactly(ApproverResolution.INACTIVE, ApproverResolution.UNLICENSED);
    }

    @Test
    void theSubmitterIsLeftOutUnlessTheProcessAllowsIt() {
        Resolved excluded = ApproverResolution.resolve(List.of(List.of(ok(SUBMITTER), ok(A))), List.of(),
                SUBMITTER, false,
                List.of(), standing());
        Resolved allowed = ApproverResolution.resolve(List.of(List.of(ok(SUBMITTER), ok(A))), List.of(),
                SUBMITTER, true,
                List.of(), standing());

        assertThat(asked(excluded)).containsExactly(A);
        assertThat(excluded.left()).containsEntry(ApproverResolution.SELF_APPROVAL, 1);
        assertThat(asked(allowed)).containsExactly(SUBMITTER, A);
    }

    @Test
    void aRequestStartedByThePlatformExcludesNobodyForBeingTheSubmitter() {
        Resolved resolved = ApproverResolution.resolve(List.of(List.of(ok(A))), List.of(), null, false, List.of(),
                standing());

        assertThat(asked(resolved)).containsExactly(A);
        assertThat(resolved.left()).isEmpty();
    }

    @Test
    void theFallbackIsAskedOnlyWhenNobodyElseIsLeft() {
        Resolved withPeople = ApproverResolution.resolve(List.of(List.of(ok(A))), List.of(ok(D)), SUBMITTER, false,
                List.of(), standing());
        Resolved nobody = ApproverResolution.resolve(List.of(List.of(new Person(A, false, true))), List.of(ok(D)),
                SUBMITTER, false, List.of(), standing());
        Resolved nothingAtAll = ApproverResolution.resolve(List.of(List.of()), List.of(), SUBMITTER, false, List.of(),
                standing());

        assertThat(asked(withPeople)).containsExactly(A);
        assertThat(withPeople.usedFallback()).isFalse();
        assertThat(asked(nobody)).containsExactly(D);
        assertThat(nobody.usedFallback()).isTrue();
        assertThat(nobody.assignees().get(0).via()).isEqualTo(Via.FALLBACK);
        assertThat(nothingAtAll.nobody()).isTrue();
    }

    @Test
    void aFallbackWhoIsAlsoLeftOutLeavesNobody() {
        Resolved resolved = ApproverResolution.resolve(List.of(List.of()), List.of(new Person(D, true, false)),
                SUBMITTER,
                false, List.of(), standing());

        assertThat(resolved.nobody()).isTrue();
        assertThat(resolved.usedFallback()).isFalse();
        assertThat(resolved.left()).containsEntry(ApproverResolution.UNLICENSED, 1);
    }

    @Test
    void aDelegateIsAddedBesideTheDelegatorAndTheDelegatorKeepsTheTask() {
        Resolved resolved = ApproverResolution.resolve(List.of(List.of(ok(A))), List.of(), SUBMITTER, false,
                List.of(new Delegation(A, B)), standing(ok(B)));

        assertThat(asked(resolved)).containsExactly(A, B);
        Assignee delegate = resolved.assignees().get(1);
        assertThat(delegate.via()).isEqualTo(Via.DELEGATED);
        assertThat(delegate.forUser()).isEqualTo(A);
    }

    @Test
    void aDelegationIsNotFollowedAFurtherStepSoThereAreNoChains() {
        // A delegates to B, and B has delegated to C: C is not asked.
        Resolved resolved = ApproverResolution.resolve(List.of(List.of(ok(A))), List.of(), SUBMITTER, false,
                List.of(new Delegation(A, B), new Delegation(B, C)), standing(ok(B), ok(C)));

        assertThat(asked(resolved)).containsExactly(A, B);
    }

    @Test
    void aDelegateWhoCannotBeAskedOrIsAlreadyAskedOrIsTheSubmitterIsNotAdded() {
        Resolved resolved = ApproverResolution.resolve(List.of(List.of(ok(A), ok(B))), List.of(), SUBMITTER, false,
                List.of(new Delegation(A, B), new Delegation(A, C), new Delegation(A, SUBMITTER), new Delegation(B, D)),
                standing(ok(B), new Person(C, true, false), ok(SUBMITTER), new Person(D, false, true)));

        // B is asked already; C has no licence; the submitter is excluded; D is not an active member.
        assertThat(asked(resolved)).containsExactly(A, B);
    }

    @Test
    void aDelegateIsNotAddedForSomeoneWhoWasLeftOut() {
        Resolved resolved = ApproverResolution.resolve(List.of(List.of(new Person(A, false, true), ok(B))), List.of(),
                SUBMITTER, false, List.of(new Delegation(A, C)), standing(ok(C)));

        assertThat(asked(resolved)).containsExactly(B);
    }
}
