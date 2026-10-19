package app.platform.sharedkernel.notice;

import java.util.Optional;
import java.util.Set;

/**
 * The closed list of in-app notices the platform can create (Sprint 25, ADR-0154). A kind says what happened and which
 * facts describe it; the words people read are built when the notice is read, from the template of the kind (the
 * platform's own or the one an administrator wrote, ADR-0155), so a change of text reaches old notices too.
 *
 * <p>Every kind belongs to a {@link Category}, which is what a person switches on or off (ADR-0156). A kind whose
 * {@link #floor() floor} is set cannot be switched off.
 */
public enum NoticeKind {

    /** The recipient has a new task in an approval. Facts: {@code process}, {@code step}. */
    APPROVAL_TASK(Category.APPROVALS, false, true, Set.of("process", "step")),

    /** A task the recipient still has open has waited longer than the process allows. Facts: {@code process}. */
    APPROVAL_REMINDER(Category.APPROVALS, false, true, Set.of("process", "step")),

    /** An approval the recipient submitted ended. Facts: {@code process}, {@code outcome}. */
    APPROVAL_DECIDED(Category.APPROVALS, false, true, Set.of("process", "outcome")),

    /** A workflow asks the recipient to look at a record. Facts: {@code workflow}. */
    WORKFLOW_NOTICE(Category.WORKFLOWS, false, true, Set.of("workflow")),

    /** The trial of the organization is ending or has ended. Facts: {@code stage}. */
    TRIAL_NOTICE(Category.ACCOUNT, true, false, Set.of("stage"));

    /** What a person switches on or off (ADR-0156). */
    public enum Category {
        /** Tasks and decisions of approvals. */
        APPROVALS,
        /** Notices a workflow sends. */
        WORKFLOWS,
        /** Things about the account and the organization's standing; cannot be switched off. */
        ACCOUNT
    }

    private final Category category;
    private final boolean floor;
    private final boolean writable;
    private final Set<String> facts;

    NoticeKind(Category category, boolean floor, boolean writable, Set<String> facts) {
        this.category = category;
        this.floor = floor;
        this.writable = writable;
        this.facts = Set.copyOf(facts);
    }

    /** The category a preference switches. */
    public Category category() {
        return category;
    }

    /** Whether the kind cannot be switched off by a person: a platform-set floor (ADR-0156). */
    public boolean floor() {
        return floor;
    }

    /** Whether an administrator may write the text of this kind (ADR-0155). */
    public boolean writable() {
        return writable;
    }

    /** The names of the facts a notice of this kind may carry. */
    public Set<String> facts() {
        // A new, separately-identified immutable copy: the enum's own field is never handed out (SpotBugs
        // EI_EXPOSE_REP).
        return Set.copyOf(facts);
    }

    /** The kind with the stored code, or empty. */
    public static Optional<NoticeKind> fromCode(String code) {
        for (NoticeKind kind : values()) {
            if (kind.name().equals(code)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
