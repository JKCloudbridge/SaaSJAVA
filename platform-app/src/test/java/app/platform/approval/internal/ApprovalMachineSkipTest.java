package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.approval.internal.ApprovalMachine.Completion;
import app.platform.approval.internal.ApprovalMachine.Done;
import app.platform.approval.internal.ApprovalMachine.Effect;
import app.platform.approval.internal.ApprovalMachine.Model;
import app.platform.approval.internal.ApprovalMachine.Refused;
import app.platform.approval.internal.ApprovalMachine.Result;
import app.platform.approval.internal.ApprovalMachine.Status;
import app.platform.approval.internal.ApprovalMachine.Step;
import app.platform.approval.internal.ApprovalMachine.StepStatus;
import app.platform.approval.internal.ApprovalMachine.Task;
import app.platform.approval.internal.ApprovalMachine.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * A step nobody can decide may be skipped when its process says so (FA4A, ADR-0187), without a database: the legal
 * moves, the refusals, and the property that any pattern of skipped steps reaches a final state, never has two active
 * steps, and never gives a skipped step a task. The engine decides <em>whether</em> a step is skipped (it resolves the
 * people); the machine decides what the request looks like after.
 */
class ApprovalMachineSkipTest {

    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();

    private static Task open(UUID person) {
        return new Task(UUID.randomUUID(), person, TaskStatus.OPEN);
    }

    private static Model threeSteps() {
        return ApprovalMachine.started(List.of(Completion.ANY, Completion.ANY, Completion.ANY));
    }

    @Test
    void theFirstStepsMaybeSkippedAndTheNextOneIsToBeResolved() {
        Result result = ApprovalMachine.skip(threeSteps(), 1);

        assertThat(result).isInstanceOf(Done.class);
        Done done = (Done) result;
        assertThat(done.effect()).isEqualTo(Effect.STEP_SKIPPED);
        assertThat(done.next()).isEqualTo(2);
        assertThat(done.model().steps().get(0).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(done.model().steps().get(0).tasks()).isEmpty();
        assertThat(done.model().status()).isEqualTo(Status.PENDING);
        assertThat(ApprovalMachine.violations(done.model())).isEmpty();
    }

    @Test
    void aMiddleStepIsSkippedAfterTheStepBeforeItWasApproved() {
        Model model = ApprovalMachine.activate(threeSteps(), 1, List.of(open(FIRST)));
        Done approved = (Done) ApprovalMachine.approve(model, FIRST);
        assertThat(approved.effect()).isEqualTo(Effect.STEP_APPROVED);

        Done skipped = (Done) ApprovalMachine.skip(approved.model(), approved.next());

        assertThat(skipped.effect()).isEqualTo(Effect.STEP_SKIPPED);
        assertThat(skipped.next()).isEqualTo(3);
        assertThat(ApprovalMachine.violations(skipped.model())).isEmpty();
        Model third = ApprovalMachine.activate(skipped.model(), 3, List.of(open(SECOND)));
        assertThat(ApprovalMachine.violations(third)).isEmpty();
    }

    @Test
    void theLastStepSkippedAfterAnApprovalApprovesTheRequest() {
        Model model = ApprovalMachine.started(List.of(Completion.ANY, Completion.ANY));
        model = ApprovalMachine.activate(model, 1, List.of(open(FIRST)));
        Done approved = (Done) ApprovalMachine.approve(model, FIRST);

        Done skipped = (Done) ApprovalMachine.skip(approved.model(), 2);

        assertThat(skipped.effect()).isEqualTo(Effect.REQUEST_APPROVED);
        assertThat(skipped.model().status()).isEqualTo(Status.APPROVED);
        assertThat(skipped.model().steps()).extracting(Step::status)
                .containsExactly(StepStatus.APPROVED, StepStatus.SKIPPED);
        assertThat(ApprovalMachine.violations(skipped.model())).isEmpty();
    }

    @Test
    void aRequestWhereEveryStepWouldBeSkippedApprovesNothingAndIsRefused() {
        Model model = ApprovalMachine.started(List.of(Completion.ANY, Completion.ANY));
        Done first = (Done) ApprovalMachine.skip(model, 1);

        Result last = ApprovalMachine.skip(first.model(), 2);

        assertThat(last).isEqualTo(new Refused("nothing_approved"));
    }

    @Test
    void onlyTheNextWaitingStepWithNoStepActiveCanBeSkipped() {
        Model started = threeSteps();
        assertThat(ApprovalMachine.skip(started, 2)).isEqualTo(new Refused("not_the_next_step"));
        Model active = ApprovalMachine.activate(started, 1, List.of(open(FIRST)));
        assertThat(ApprovalMachine.skip(active, 2)).isEqualTo(new Refused("not_between_steps"));
        Model recalled = ((Done) ApprovalMachine.recall(active)).model();
        assertThat(ApprovalMachine.skip(recalled, 1)).isEqualTo(new Refused("not_between_steps"));
    }

    @Test
    void aStepAfterASkippedOneCanBeActivated() {
        Done skipped = (Done) ApprovalMachine.skip(threeSteps(), 1);

        Model second = ApprovalMachine.activate(skipped.model(), 2, List.of(open(FIRST)));

        assertThat(second.active()).isPresent();
        assertThat(ApprovalMachine.violations(second)).isEmpty();
    }

    // ---- the property ----

    @Provide
    Arbitrary<List<Completion>> processes() {
        return Arbitraries.of(Completion.values()).list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<Boolean>> nobody() {
        return Arbitraries.of(true, false).list().ofSize(5);
    }

    /**
     * The engine's loop in miniature: resolve the next step; when nobody can decide it, skip it; otherwise activate it
     * and let everyone asked approve. Whatever the pattern, the request ends approved with at least one approved step,
     * or (when every step had nobody) the last skip is refused and nothing was approved.
     */
    @Property
    void anyPatternOfSkippedStepsReachesAFinalStateWithAllTheRulesHolding(
            @ForAll("processes") List<Completion> completions, @ForAll("nobody") List<Boolean> nobody) {
        Model model = ApprovalMachine.started(completions);
        int at = 1;
        boolean approvedOne = false;
        while (true) {
            if (nobody.get(at - 1)) {
                Result skipped = ApprovalMachine.skip(model, at);
                if (skipped instanceof Refused refused) {
                    assertThat(refused.reason()).isEqualTo("nothing_approved");
                    assertThat(approvedOne).isFalse();
                    assertThat(model.steps()).allMatch(step -> step.status() != StepStatus.APPROVED);
                    return;
                }
                Done done = (Done) skipped;
                model = done.model();
                assertThat(ApprovalMachine.violations(model)).isEmpty();
                if (done.effect() == Effect.REQUEST_APPROVED) {
                    break;
                }
                at = done.next();
                continue;
            }
            List<Task> tasks = new ArrayList<>(List.of(open(FIRST), open(SECOND)));
            model = ApprovalMachine.activate(model, at, tasks);
            assertThat(model.steps().stream().filter(step -> step.status() == StepStatus.ACTIVE)).hasSize(1);
            assertThat(ApprovalMachine.violations(model)).isEmpty();
            Done done = (Done) ApprovalMachine.approve(model, FIRST);
            if (done.effect() == Effect.TASK_APPROVED) {
                done = (Done) ApprovalMachine.approve(done.model(), SECOND);
            }
            approvedOne = true;
            model = done.model();
            assertThat(ApprovalMachine.violations(model)).isEmpty();
            if (done.effect() == Effect.REQUEST_APPROVED) {
                break;
            }
            at = done.next();
        }
        assertThat(model.status()).isEqualTo(Status.APPROVED);
        assertThat(model.steps()).allMatch(step -> step.status() == StepStatus.APPROVED
                || step.status() == StepStatus.SKIPPED && step.tasks().isEmpty());
        assertThat(model.steps()).anyMatch(step -> step.status() == StepStatus.APPROVED);
    }
}
