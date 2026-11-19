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
import app.platform.approval.internal.ApprovalMachine.Task;
import app.platform.approval.internal.ApprovalMachine.TaskStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * The people a waiting step asks change when the reviewers chosen for the record change (2026-11-17, a note on
 * ADR-0260), without a database: a person taken off loses an open task, a person added gets one, a person kept keeps
 * theirs, a decided or handed-on task never moves, a person whose task was closed this way gets it back, and nothing
 * happens to a step that needs everyone or a request that has ended. A property holds the invariants of the machine
 * over any sequence of changes and approvals.
 */
class ApprovalMachineAskedChangedTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private static Model active(Completion completion, Task... tasks) {
        Model started = ApprovalMachine.started(List.of(completion));
        return ApprovalMachine.activate(started, 1, List.of(tasks));
    }

    private static Task task(UUID person, TaskStatus status) {
        return new Task(UUID.randomUUID(), person, status);
    }

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static TaskStatus statusOf(Model model, UUID person) {
        return model.active().orElseThrow().tasks().stream().filter(task -> task.assignee().equals(person))
                .findFirst().orElseThrow().status();
    }

    @Test
    void aPersonTakenOffLosesTheirOpenTaskAndAPersonAddedGetsOne() {
        Task first = task(A, TaskStatus.OPEN);
        Result result = ApprovalMachine.changeAsked(active(Completion.ANY, first), Set.of(A), List.of(B), ids(1));

        assertThat(result).isInstanceOf(Done.class);
        Done done = (Done) result;
        assertThat(done.effect()).isEqualTo(Effect.ASKED_CHANGED);
        assertThat(done.closed()).containsExactly(first.id());
        assertThat(statusOf(done.model(), A)).isEqualTo(TaskStatus.CLOSED);
        assertThat(statusOf(done.model(), B)).isEqualTo(TaskStatus.OPEN);
        assertThat(done.model().status()).isEqualTo(Status.PENDING);
        assertThat(ApprovalMachine.violations(done.model())).isEmpty();
    }

    @Test
    void aPersonKeptKeepsTheirTaskAndNothingChangedIsSaidSo() {
        Task kept = task(A, TaskStatus.OPEN);

        assertThat(ApprovalMachine.changeAsked(active(Completion.ANY, kept), Set.of(), List.of(A), ids(1)))
                .isEqualTo(new Refused("unchanged"));
    }

    @Test
    void aPersonWhoseTaskWasClosedThisWayGetsItBackRatherThanASecondTask() {
        Model once = ((Done) ApprovalMachine.changeAsked(active(Completion.ANY, task(A, TaskStatus.OPEN)), Set.of(A),
                List.of(B), ids(1))).model();

        Done again = (Done) ApprovalMachine.changeAsked(once, Set.of(B), List.of(A), ids(1));

        assertThat(again.model().active().orElseThrow().tasks()).hasSize(2);
        assertThat(statusOf(again.model(), A)).isEqualTo(TaskStatus.OPEN);
        assertThat(statusOf(again.model(), B)).isEqualTo(TaskStatus.CLOSED);
        assertThat(ApprovalMachine.violations(again.model())).isEmpty();
    }

    @Test
    void aTaskHandedOnIsNeitherClosedNorAskedAgain() {
        Task handed = task(A, TaskStatus.REASSIGNED);
        Task taker = task(C, TaskStatus.OPEN);

        Done done = (Done) ApprovalMachine.changeAsked(active(Completion.ANY, handed, taker), Set.of(), List.of(A, B),
                ids(2));

        assertThat(statusOf(done.model(), A)).isEqualTo(TaskStatus.REASSIGNED);
        assertThat(statusOf(done.model(), C)).as("asked by the person who handed it on").isEqualTo(TaskStatus.OPEN);
        assertThat(statusOf(done.model(), B)).isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void whenNobodyIsLeftTheStepStaysActiveAndWaitsWithNoOpenTask() {
        Done done = (Done) ApprovalMachine.changeAsked(active(Completion.ANY, task(A, TaskStatus.OPEN)), Set.of(A),
                List.of(), ids(0));

        Step step = done.model().active().orElseThrow();
        assertThat(step.open()).isZero();
        assertThat(done.model().status()).isEqualTo(Status.PENDING);
        assertThat(ApprovalMachine.violations(done.model())).isEmpty();
    }

    @Test
    void aStepThatNeedsEveryoneAndARequestThatEndedAreLeftAlone() {
        assertThat(ApprovalMachine.changeAsked(active(Completion.ALL, task(A, TaskStatus.OPEN)), Set.of(A),
                List.of(B), ids(1))).isEqualTo(new Refused("needs_everyone"));
        Model ended = ((Done) ApprovalMachine.approve(active(Completion.ANY, task(A, TaskStatus.OPEN)), A)).model();
        assertThat(ApprovalMachine.changeAsked(ended, Set.of(), List.of(B), ids(1)))
                .isEqualTo(new Refused("not_pending"));
    }

    @Test
    void aPersonAddedThenDecidesTheStep() {
        Model changed = ((Done) ApprovalMachine.changeAsked(active(Completion.ANY, task(A, TaskStatus.OPEN)),
                Set.of(A), List.of(B), ids(1))).model();

        assertThat(ApprovalMachine.approve(changed, A)).isEqualTo(new Refused("no_open_task"));
        Done approved = (Done) ApprovalMachine.approve(changed, B);
        assertThat(approved.effect()).isEqualTo(Effect.REQUEST_APPROVED);
        assertThat(ApprovalMachine.violations(approved.model())).isEmpty();
    }

    @Property(tries = 300)
    void anySequenceOfChangesAndApprovalsKeepsTheMachineSound(@ForAll("moves") List<List<Integer>> moves) {
        List<UUID> people = List.of(A, B, C, UUID.randomUUID());
        Model model = active(Completion.ANY, task(A, TaskStatus.OPEN));
        for (List<Integer> move : moves) {
            if (model.status() != Status.PENDING) {
                break;
            }
            if (move.get(0) == 0) {
                // approve by someone
                Result result = ApprovalMachine.approve(model, people.get(move.get(1)));
                if (result instanceof Done done) {
                    model = done.model();
                }
            } else {
                Set<UUID> chosen = new HashSet<>();
                for (int i = 0; i < people.size(); i++) {
                    if ((move.get(1) & (1 << i)) != 0) {
                        chosen.add(people.get(i));
                    }
                }
                Step step = model.active().orElseThrow();
                Set<UUID> withdrawn = new HashSet<>();
                step.tasks().stream().filter(task -> task.status() == TaskStatus.OPEN
                        && !chosen.contains(task.assignee())).forEach(task -> withdrawn.add(task.assignee()));
                Result result = ApprovalMachine.changeAsked(model, withdrawn, List.copyOf(chosen), ids(chosen.size()));
                if (result instanceof Done done) {
                    model = done.model();
                    Step after = model.active().orElseThrow();
                    for (UUID person : chosen) {
                        assertThat(after.holds(person)).as("a chosen person holds a task").isTrue();
                    }
                    for (Task one : after.tasks()) {
                        if (one.status() == TaskStatus.OPEN) {
                            assertThat(chosen).as("only a chosen person holds an open task")
                                    .contains(one.assignee());
                        }
                    }
                }
            }
            assertThat(ApprovalMachine.violations(model)).isEmpty();
        }
    }

    @Provide
    Arbitrary<List<List<Integer>>> moves() {
        Arbitrary<List<Integer>> move = Arbitraries.integers().between(0, 1).flatMap(kind -> kind == 0
                ? Arbitraries.integers().between(0, 3).map(person -> List.of(0, person))
                : Arbitraries.integers().between(0, 15).map(mask -> List.of(1, mask)));
        return move.list().ofMaxSize(12);
    }
}
