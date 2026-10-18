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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The states of an approval request and what each action does to them (ADR-0145, ADR-0147), without a database: every
 * legal
 * transition and the refusals. The property test holds the invariants over any sequence of actions.
 */
class ApprovalMachineTest {

    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID FINANCE = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static Task open(UUID person) {
        return new Task(UUID.randomUUID(), person, TaskStatus.OPEN);
    }

    /** A two-step request, the first step active and asking the manager. */
    private static Model twoSteps(Completion first, UUID... asked) {
        Model started = ApprovalMachine.started(List.of(first, Completion.ANY));
        return ApprovalMachine.activate(started, 1, java.util.Arrays.stream(asked)
                .map(ApprovalMachineTest::open).toList());
    }

    private static Done done(Result result) {
        assertThat(result).isInstanceOf(Done.class);
        return (Done) result;
    }

    @Test
    void aNewRequestWaitsAtItsFirstStepOnceThatStepIsActivated() {
        Model model = twoSteps(Completion.ANY, MANAGER);

        assertThat(model.status()).isEqualTo(Status.PENDING);
        assertThat(model.active()).hasValueSatisfying(step -> assertThat(step.position()).isEqualTo(1));
        assertThat(ApprovalMachine.violations(model)).isEmpty();
    }

    @Test
    void approvingTheFirstStepOfTwoActivatesTheNextAndApprovingTheLastEndsTheRequest() {
        Model model = twoSteps(Completion.ANY, MANAGER);

        Done first = done(ApprovalMachine.approve(model, MANAGER));
        assertThat(first.effect()).isEqualTo(Effect.STEP_APPROVED);
        assertThat(first.next()).isEqualTo(2);
        Model second = ApprovalMachine.activate(first.model(), 2, List.of(open(FINANCE)));
        assertThat(ApprovalMachine.violations(second)).isEmpty();

        Done last = done(ApprovalMachine.approve(second, FINANCE));
        assertThat(last.effect()).isEqualTo(Effect.REQUEST_APPROVED);
        assertThat(last.model().status()).isEqualTo(Status.APPROVED);
        assertThat(last.model().active()).isEmpty();
        assertThat(ApprovalMachine.violations(last.model())).isEmpty();
    }

    @Test
    void onAnyOneStepTheFirstApprovalCompletesItAndClosesTheOthersTasks() {
        Model model = twoSteps(Completion.ANY, MANAGER, OTHER);

        Done approved = done(ApprovalMachine.approve(model, MANAGER));

        Step step = approved.model().steps().get(0);
        assertThat(step.status()).isEqualTo(StepStatus.APPROVED);
        assertThat(step.tasks()).extracting(Task::status).containsExactly(TaskStatus.APPROVED, TaskStatus.CLOSED);
        assertThat(approved.closed()).hasSize(1);
        // The second person's click comes too late: the step is not active, the call is refused.
        assertThat(ApprovalMachine.approve(approved.model(), OTHER)).isInstanceOf(Refused.class);
    }

    @Test
    void onAnAllStepTheStepWaitsUntilEveryTaskIsApproved() {
        Model model = twoSteps(Completion.ALL, MANAGER, OTHER);

        Done one = done(ApprovalMachine.approve(model, MANAGER));
        assertThat(one.effect()).isEqualTo(Effect.TASK_APPROVED);
        assertThat(one.model().active()).isPresent();

        Done both = done(ApprovalMachine.approve(one.model(), OTHER));
        assertThat(both.effect()).isEqualTo(Effect.STEP_APPROVED);
        assertThat(both.model().steps().get(0).status()).isEqualTo(StepStatus.APPROVED);
    }

    @Test
    void oneRejectionEndsTheStepAndTheRequestWhateverTheCompletionRule() {
        for (Completion completion : Completion.values()) {
            Model model = twoSteps(completion, MANAGER, OTHER);

            Done rejected = done(ApprovalMachine.reject(model, OTHER));

            assertThat(rejected.effect()).isEqualTo(Effect.REQUEST_REJECTED);
            assertThat(rejected.model().status()).isEqualTo(Status.REJECTED);
            assertThat(rejected.model().steps()).extracting(Step::status)
                    .containsExactly(StepStatus.REJECTED, StepStatus.SKIPPED);
            assertThat(rejected.model().steps().get(0).tasks()).extracting(Task::status)
                    .containsExactly(TaskStatus.CLOSED, TaskStatus.REJECTED);
            assertThat(ApprovalMachine.violations(rejected.model())).isEmpty();
        }
    }

    @Test
    void aPersonWithoutAnOpenTaskCannotDecide() {
        Model model = twoSteps(Completion.ANY, MANAGER);

        assertThat(ApprovalMachine.approve(model, OTHER)).isEqualTo(new Refused("no_open_task"));
        assertThat(ApprovalMachine.reject(model, OTHER)).isEqualTo(new Refused("no_open_task"));
    }

    @Test
    void aRequestThatEndedCannotBeDecidedAgainRecalledOrCancelled() {
        Model ended = done(ApprovalMachine.reject(twoSteps(Completion.ANY, MANAGER), MANAGER)).model();

        assertThat(ApprovalMachine.approve(ended, MANAGER)).isEqualTo(new Refused("not_pending"));
        assertThat(ApprovalMachine.recall(ended)).isEqualTo(new Refused("not_pending"));
        assertThat(ApprovalMachine.cancel(ended)).isEqualTo(new Refused("not_pending"));
    }

    @Test
    void recallAndCancelEndTheRequestSkipThePendingStepsAndCloseTheOpenTasks() {
        Model model = twoSteps(Completion.ANY, MANAGER, OTHER);

        Done recalled = done(ApprovalMachine.recall(model));
        Done cancelled = done(ApprovalMachine.cancel(model));

        assertThat(recalled.model().status()).isEqualTo(Status.RECALLED);
        assertThat(cancelled.model().status()).isEqualTo(Status.CANCELLED);
        for (Done end : List.of(recalled, cancelled)) {
            assertThat(end.model().steps()).extracting(Step::status)
                    .containsExactly(StepStatus.SKIPPED, StepStatus.SKIPPED);
            assertThat(end.closed()).hasSize(2);
            assertThat(ApprovalMachine.violations(end.model())).isEmpty();
        }
    }

    @Test
    void reassigningHandsTheTaskToSomeoneWhoHoldsNoTaskOfTheStepAndNeverBack() {
        Model model = twoSteps(Completion.ANY, MANAGER);

        Done handed = done(ApprovalMachine.reassign(model, MANAGER, OTHER, UUID.randomUUID()));

        assertThat(handed.model().steps().get(0).tasks()).extracting(Task::status)
                .containsExactly(TaskStatus.REASSIGNED, TaskStatus.OPEN);
        // The first person holds a task of the step (handed on), so it cannot come back to them, and not to oneself.
        assertThat(ApprovalMachine.reassign(handed.model(), OTHER, MANAGER, UUID.randomUUID()))
                .isEqualTo(new Refused("already_asked"));
        assertThat(ApprovalMachine.reassign(handed.model(), OTHER, OTHER, UUID.randomUUID()))
                .isEqualTo(new Refused("already_asked"));
        // The person who handed it on no longer has a task to decide or hand on.
        assertThat(ApprovalMachine.approve(handed.model(), MANAGER)).isEqualTo(new Refused("no_open_task"));
        assertThat(ApprovalMachine.violations(handed.model())).isEmpty();
    }

    @Test
    void anAssignmentAddsAPersonOnceAndTheNewPersonCanThenDecide() {
        Model empty = ApprovalMachine.activate(ApprovalMachine.started(List.of(Completion.ANY)), 1, List.of());

        Done added = done(ApprovalMachine.assign(empty, OTHER, UUID.randomUUID()));

        assertThat(ApprovalMachine.assign(added.model(), OTHER, UUID.randomUUID()))
                .isEqualTo(new Refused("already_asked"));
        Done approved = done(ApprovalMachine.approve(added.model(), OTHER));
        assertThat(approved.effect()).isEqualTo(Effect.REQUEST_APPROVED);
    }

    @Test
    void aStepIsActivatedOnlyWhenTheEarlierOnesAreApprovedAndNoneIsActive() {
        Model started = ApprovalMachine.started(List.of(Completion.ANY, Completion.ANY));
        Model first = ApprovalMachine.activate(started, 1, List.of(open(MANAGER)));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ApprovalMachine.activate(first, 2, List.of(open(FINANCE))));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ApprovalMachine.activate(started, 2, List.of(open(FINANCE))));
    }

    @Test
    void theInvariantsNameWhatIsBroken() {
        Model pendingWithNothingActive = ApprovalMachine.started(List.of(Completion.ANY));

        assertThat(ApprovalMachine.violations(pendingWithNothingActive))
                .contains("a pending request has exactly one active step");
    }
}
