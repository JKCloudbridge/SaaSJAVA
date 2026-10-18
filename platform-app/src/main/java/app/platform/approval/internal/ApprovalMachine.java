package app.platform.approval.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The states of an approval request and what each action does to them (ADR-0145, ADR-0147), as a pure function of an
 * immutable model: no database, no clock, no person lookup. The service loads the model from the rows, asks this class
 * what the action does, and writes the difference. That keeps the rules in one small place that a property test can
 * hold
 * to its invariants ({@link #violations}) over every sequence of actions.
 *
 * <p>The rules, in words: a request is {@code PENDING} while one step is {@code ACTIVE}. A task is approved by the
 * person
 * it was given to. A step with completion {@code ANY} is approved by the first approval and its other tasks are
 * {@code CLOSED}; a step with {@code ALL} is approved when no task is open any more. One rejection ends the step and
 * the
 * request as {@code REJECTED}, whatever the completion. A recall or a cancel ends the request; the steps not yet
 * decided
 * are {@code SKIPPED} and the tasks still open are {@code CLOSED}. A task is handed to a person who holds no task of
 * the
 * step yet, so it never goes back and never loops; the old task is {@code REASSIGNED}.
 */
final class ApprovalMachine {

    /** The states of a request. */
    enum Status { PENDING, APPROVED, REJECTED, RECALLED, CANCELLED }

    /** The states of a step. */
    enum StepStatus { WAITING, ACTIVE, APPROVED, REJECTED, SKIPPED }

    /** The states of a task. */
    enum TaskStatus { OPEN, APPROVED, REJECTED, REASSIGNED, CLOSED }

    /** How many of the people asked must approve. */
    enum Completion { ANY, ALL }

    /** What an action did, so the caller can write the history and decide what follows. */
    enum Effect {
        /** A task was approved and the step is still waiting for others. */
        TASK_APPROVED,
        /** The step was approved; the next step must be activated (see {@link Done#next()}). */
        STEP_APPROVED,
        /** The last step was approved. */
        REQUEST_APPROVED,
        /** A task was rejected: the step and the request are rejected. */
        REQUEST_REJECTED,
        /** The request was withdrawn. */
        REQUEST_RECALLED,
        /** An administrator ended the request. */
        REQUEST_CANCELLED,
        /** A task was handed to another person. */
        TASK_REASSIGNED,
        /** A person was added to the active step. */
        TASK_ADDED
    }

    /**
     * One task.
     *
     * @param id the task
     * @param assignee the person asked
     * @param status its state
     */
    record Task(UUID id, UUID assignee, TaskStatus status) {
    }

    /**
     * One step.
     *
     * @param position from 1
     * @param completion how many must approve
     * @param status its state
     * @param tasks the people asked
     */
    record Step(int position, Completion completion, StepStatus status, List<Task> tasks) {

        /** Copies the list. */
        Step {
            tasks = List.copyOf(tasks);
        }

        boolean holds(UUID person) {
            return tasks.stream().anyMatch(task -> task.assignee().equals(person));
        }

        long open() {
            return tasks.stream().filter(task -> task.status() == TaskStatus.OPEN).count();
        }
    }

    /**
     * A request.
     *
     * @param status its state
     * @param steps its steps in order
     */
    record Model(Status status, List<Step> steps) {

        /** Copies the list. */
        Model {
            steps = List.copyOf(steps);
        }

        /** The active step, if the request is pending. */
        Optional<Step> active() {
            return steps.stream().filter(step -> step.status() == StepStatus.ACTIVE).findFirst();
        }
    }

    /** The result of an action. */
    sealed interface Result permits Refused, Done {
    }

    /**
     * The action cannot be done.
     *
     * @param reason a stable key
     */
    record Refused(String reason) implements Result {
    }

    /**
     * The action was done.
     *
     * @param model the request after it
     * @param effect what it did
     * @param closed the tasks that were closed because the step or the request ended
     * @param next the position of the step that must be activated next, or 0
     */
    record Done(Model model, Effect effect, List<UUID> closed, int next) implements Result {

        /** Copies the list. */
        public Done {
            closed = List.copyOf(closed);
        }
    }

    private ApprovalMachine() {
    }

    /** A new request: every step waits, nothing is active yet. */
    static Model started(List<Completion> completions) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < completions.size(); i++) {
            steps.add(new Step(i + 1, completions.get(i), StepStatus.WAITING, List.of()));
        }
        return new Model(Status.PENDING, steps);
    }

    /** Makes a waiting step the active one, with the tasks of the people asked (there may be none). */
    static Model activate(Model model, int position, List<Task> tasks) {
        if (model.status() != Status.PENDING || model.active().isPresent()) {
            throw new IllegalStateException("A step is activated only when the request is pending and none is active");
        }
        Step step = step(model, position);
        boolean earlierDone = model.steps().stream().filter(other -> other.position() < position)
                .allMatch(other -> other.status() == StepStatus.APPROVED);
        if (step.status() != StepStatus.WAITING || !earlierDone) {
            throw new IllegalStateException("Only the next waiting step can be activated");
        }
        return replace(model, new Step(position, step.completion(), StepStatus.ACTIVE, tasks), model.status());
    }

    /** The person approves their open task of the active step. */
    static Result approve(Model model, UUID person) {
        Step step = pendingStep(model);
        if (step == null) {
            return new Refused("not_pending");
        }
        Task task = openTask(step, person);
        if (task == null) {
            return new Refused("no_open_task");
        }
        List<Task> tasks = withTask(step, task.id(), TaskStatus.APPROVED);
        boolean complete = step.completion() == Completion.ANY
                || tasks.stream().noneMatch(other -> other.status() == TaskStatus.OPEN);
        if (!complete) {
            return new Done(replace(model, new Step(step.position(), step.completion(), StepStatus.ACTIVE, tasks),
                    Status.PENDING), Effect.TASK_APPROVED, List.of(), 0);
        }
        List<UUID> closed = new ArrayList<>();
        List<Task> settled = closeOpen(tasks, closed);
        Model after = replace(model, new Step(step.position(), step.completion(), StepStatus.APPROVED, settled),
                Status.PENDING);
        if (step.position() == model.steps().size()) {
            return new Done(new Model(Status.APPROVED, after.steps()), Effect.REQUEST_APPROVED, closed, 0);
        }
        return new Done(after, Effect.STEP_APPROVED, closed, step.position() + 1);
    }

    /** The person rejects their open task: the step and the request are rejected. */
    static Result reject(Model model, UUID person) {
        Step step = pendingStep(model);
        if (step == null) {
            return new Refused("not_pending");
        }
        Task task = openTask(step, person);
        if (task == null) {
            return new Refused("no_open_task");
        }
        List<UUID> closed = new ArrayList<>();
        List<Task> tasks = closeOpen(withTask(step, task.id(), TaskStatus.REJECTED), closed);
        Model after = replace(model, new Step(step.position(), step.completion(), StepStatus.REJECTED, tasks),
                Status.REJECTED);
        return new Done(skipTheRest(after), Effect.REQUEST_REJECTED, closed, 0);
    }

    /** The request is withdrawn. */
    static Result recall(Model model) {
        return end(model, Status.RECALLED, Effect.REQUEST_RECALLED);
    }

    /** An administrator ends the request. */
    static Result cancel(Model model) {
        return end(model, Status.CANCELLED, Effect.REQUEST_CANCELLED);
    }

    /** The person hands their open task of the active step to another person, who holds no task of the step yet. */
    static Result reassign(Model model, UUID from, UUID to, UUID newTask) {
        Step step = pendingStep(model);
        if (step == null) {
            return new Refused("not_pending");
        }
        Task task = openTask(step, from);
        if (task == null) {
            return new Refused("no_open_task");
        }
        if (from.equals(to) || step.holds(to)) {
            return new Refused("already_asked");
        }
        List<Task> tasks = new ArrayList<>(withTask(step, task.id(), TaskStatus.REASSIGNED));
        tasks.add(new Task(newTask, to, TaskStatus.OPEN));
        return new Done(replace(model, new Step(step.position(), step.completion(), StepStatus.ACTIVE, tasks),
                Status.PENDING), Effect.TASK_REASSIGNED, List.of(), 0);
    }

    /** A person is added to the active step (an administrator assigns somebody). */
    static Result assign(Model model, UUID to, UUID newTask) {
        Step step = pendingStep(model);
        if (step == null) {
            return new Refused("not_pending");
        }
        if (step.holds(to)) {
            return new Refused("already_asked");
        }
        List<Task> tasks = new ArrayList<>(step.tasks());
        tasks.add(new Task(newTask, to, TaskStatus.OPEN));
        return new Done(replace(model, new Step(step.position(), step.completion(), StepStatus.ACTIVE, tasks),
                Status.PENDING), Effect.TASK_ADDED, List.of(), 0);
    }

    /**
     * What must always hold, whatever was done. Used by the property test and as a second line of defence before a
     * model is written.
     *
     * @return the broken rules, empty when the model is sound
     */
    static List<String> violations(Model model) {
        List<String> broken = new ArrayList<>();
        long active = model.steps().stream().filter(step -> step.status() == StepStatus.ACTIVE).count();
        if (model.status() == Status.PENDING && active != 1) {
            broken.add("a pending request has exactly one active step");
        }
        if (model.status() != Status.PENDING && active != 0) {
            broken.add("an ended request has no active step");
        }
        boolean approved = model.steps().stream().anyMatch(step -> step.status() == StepStatus.APPROVED);
        boolean rejected = model.steps().stream().anyMatch(step -> step.status() == StepStatus.REJECTED);
        if (model.status() == Status.APPROVED && model.steps().stream()
                .anyMatch(step -> step.status() != StepStatus.APPROVED)) {
            broken.add("an approved request has every step approved");
        }
        if (model.status() == Status.REJECTED && !rejected) {
            broken.add("a rejected request has a rejected step");
        }
        if (rejected && model.status() != Status.REJECTED) {
            broken.add("a rejected step ends the request as rejected");
        }
        if (approved && rejected && model.steps().stream().anyMatch(step -> step.status() == StepStatus.APPROVED
                && step.position() > model.steps().stream().filter(other -> other.status() == StepStatus.REJECTED)
                        .mapToInt(Step::position).min().orElse(Integer.MAX_VALUE))) {
            broken.add("no step after a rejected one is approved");
        }
        for (Step step : model.steps()) {
            boolean ended = model.status() != Status.PENDING;
            if (ended && step.open() > 0) {
                broken.add("an ended request has no open task");
            }
            if (step.status() == StepStatus.APPROVED && step.completion() == Completion.ANY
                    && step.tasks().stream().noneMatch(task -> task.status() == TaskStatus.APPROVED)) {
                broken.add("an approved step has an approval");
            }
            if (step.status() == StepStatus.APPROVED && step.tasks().stream()
                    .anyMatch(task -> task.status() == TaskStatus.REJECTED)) {
                broken.add("a step is not both approved and rejected");
            }
            if (step.status() == StepStatus.APPROVED && step.completion() == Completion.ALL
                    && step.tasks().stream().anyMatch(task -> task.status() == TaskStatus.OPEN)) {
                broken.add("a step that needs everyone is not approved while a task is open");
            }
            long distinct = step.tasks().stream().map(Task::assignee).distinct().count();
            if (distinct != step.tasks().size()) {
                broken.add("a person holds at most one task of a step");
            }
        }
        return broken;
    }

    // ---- helpers ----

    private static Result end(Model model, Status status, Effect effect) {
        if (model.status() != Status.PENDING) {
            return new Refused("not_pending");
        }
        List<UUID> closed = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        for (Step step : model.steps()) {
            if (step.status() == StepStatus.ACTIVE) {
                steps.add(new Step(step.position(), step.completion(), StepStatus.SKIPPED,
                        closeOpen(step.tasks(), closed)));
            } else if (step.status() == StepStatus.WAITING) {
                steps.add(new Step(step.position(), step.completion(), StepStatus.SKIPPED, step.tasks()));
            } else {
                steps.add(step);
            }
        }
        return new Done(new Model(status, steps), effect, closed, 0);
    }

    private static Model skipTheRest(Model model) {
        List<Step> steps = new ArrayList<>();
        for (Step step : model.steps()) {
            steps.add(step.status() == StepStatus.WAITING
                    ? new Step(step.position(), step.completion(), StepStatus.SKIPPED, step.tasks()) : step);
        }
        return new Model(model.status(), steps);
    }

    private static Step pendingStep(Model model) {
        return model.status() == Status.PENDING ? model.active().orElse(null) : null;
    }

    private static Task openTask(Step step, UUID person) {
        return step.tasks().stream().filter(task -> task.assignee().equals(person)
                && task.status() == TaskStatus.OPEN).findFirst().orElse(null);
    }

    private static List<Task> withTask(Step step, UUID task, TaskStatus status) {
        List<Task> tasks = new ArrayList<>();
        for (Task other : step.tasks()) {
            tasks.add(other.id().equals(task) ? new Task(other.id(), other.assignee(), status) : other);
        }
        return tasks;
    }

    private static List<Task> closeOpen(List<Task> tasks, List<UUID> closed) {
        List<Task> settled = new ArrayList<>();
        for (Task task : tasks) {
            if (task.status() == TaskStatus.OPEN) {
                closed.add(task.id());
                settled.add(new Task(task.id(), task.assignee(), TaskStatus.CLOSED));
            } else {
                settled.add(task);
            }
        }
        return settled;
    }

    private static Step step(Model model, int position) {
        return model.steps().stream().filter(step -> step.position() == position).findFirst()
                .orElseThrow(() -> new IllegalStateException("There is no such step"));
    }

    private static Model replace(Model model, Step changed, Status status) {
        List<Step> steps = new ArrayList<>();
        for (Step step : model.steps()) {
            steps.add(step.position() == changed.position() ? changed : step);
        }
        return new Model(status, steps);
    }
}
