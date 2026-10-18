package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.approval.internal.ApprovalMachine.Completion;
import app.platform.approval.internal.ApprovalMachine.Done;
import app.platform.approval.internal.ApprovalMachine.Effect;
import app.platform.approval.internal.ApprovalMachine.Model;
import app.platform.approval.internal.ApprovalMachine.Result;
import app.platform.approval.internal.ApprovalMachine.Status;
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

/**
 * No sequence of actions leaves a request in a state the rules forbid (ADR-0145 decision 5): never a step both approved
 * and
 * rejected, never a pending request without exactly one active step, never an ended request with an open task or an
 * active
 * step, never a person with two tasks of a step. The actions are chosen at random from a small group of people, some of
 * them
 * not asked at all, so refusals are part of every run. The machine itself decides what is possible; the property only
 * looks at what it allowed.
 */
class ApprovalMachinePropertyTest {

    private enum Act { APPROVE, REJECT, RECALL, CANCEL, REASSIGN, ASSIGN }

    private record Move(Act act, int who, int to) {
    }

    private static final List<UUID> PEOPLE = java.util.stream.Stream.generate(UUID::randomUUID).limit(5).toList();

    @Provide
    Arbitrary<List<Completion>> processes() {
        return Arbitraries.of(Completion.values()).list().ofMinSize(1).ofMaxSize(4);
    }

    @Provide
    Arbitrary<List<Move>> moves() {
        Arbitrary<Move> one = Arbitraries.integers().between(0, 5).flatMap(act -> Arbitraries.integers().between(0, 4)
                .flatMap(who -> Arbitraries.integers().between(0, 4).map(to -> new Move(Act.values()[act], who, to))));
        return one.list().ofMinSize(0).ofMaxSize(40);
    }

    @Property
    void noSequenceOfActionsBreaksAnInvariant(@ForAll("processes") List<Completion> completions,
            @ForAll("moves") List<Move> moves) {
        Model model = ApprovalMachine.started(completions);
        model = activate(model, 1, PEOPLE.subList(0, 2));
        assertThat(ApprovalMachine.violations(model)).isEmpty();

        for (Move move : moves) {
            Result result = switch (move.act()) {
                case APPROVE -> ApprovalMachine.approve(model, PEOPLE.get(move.who()));
                case REJECT -> ApprovalMachine.reject(model, PEOPLE.get(move.who()));
                case RECALL -> ApprovalMachine.recall(model);
                case CANCEL -> ApprovalMachine.cancel(model);
                case REASSIGN -> ApprovalMachine.reassign(model, PEOPLE.get(move.who()), PEOPLE.get(move.to()),
                        UUID.randomUUID());
                case ASSIGN -> ApprovalMachine.assign(model, PEOPLE.get(move.to()), UUID.randomUUID());
            };
            if (result instanceof Done done) {
                model = done.model();
                if (done.effect() == Effect.STEP_APPROVED) {
                    // The next step becomes active in the same transaction, with the first two people asked.
                    model = activate(model, done.next(), PEOPLE.subList(0, 2));
                }
                assertThat(ApprovalMachine.violations(model)).as(move.toString()).isEmpty();
            }
            if (model.status() != Status.PENDING) {
                // Once ended, nothing can be done any more.
                assertThat(ApprovalMachine.approve(model, PEOPLE.get(0))).isNotInstanceOf(Done.class);
                assertThat(ApprovalMachine.recall(model)).isNotInstanceOf(Done.class);
                break;
            }
        }
    }

    @Property
    void aTaskIsNeverDecidedTwice(@ForAll("processes") List<Completion> completions) {
        Model model = activate(ApprovalMachine.started(completions), 1, PEOPLE.subList(0, 3));

        Result first = ApprovalMachine.approve(model, PEOPLE.get(0));
        assertThat(first).isInstanceOf(Done.class);
        Model after = ((Done) first).model();
        // The same person cannot approve the same task again, whatever the state is now.
        assertThat(ApprovalMachine.approve(after, PEOPLE.get(0))).isNotInstanceOf(Done.class);
        long decided = after.steps().stream().flatMap(step -> step.tasks().stream())
                .filter(task -> task.assignee().equals(PEOPLE.get(0)) && task.status() == TaskStatus.APPROVED).count();
        assertThat(decided).isEqualTo(1);
    }

    private static Model activate(Model model, int position, List<UUID> asked) {
        List<Task> tasks = new ArrayList<>();
        asked.forEach(person -> tasks.add(new Task(UUID.randomUUID(), person, TaskStatus.OPEN)));
        return ApprovalMachine.activate(model, position, tasks);
    }
}
