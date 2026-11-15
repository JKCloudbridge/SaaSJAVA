package app.platform.sharedkernel.notice;

import app.platform.sharedkernel.mail.MailTemplate;
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
    TRIAL_NOTICE(Category.ACCOUNT, true, false, Set.of("stage")),

    /**
     * Something happened to a change request on a record (FA5, ADR-0191). Facts: {@code event} (a stable key: one was
     * made, a comment was added, it was accepted, declined or turned into a new version), and {@code application} and
     * {@code entry}, the address parts of the page that opens the record, and (FA6E) {@code request}, the request
     * itself, and {@code tab}, the part of that page that lists requests, which a page that lists requests
     * opens. The text is the platform's own.
     */
    CHANGE_REQUEST(Category.REQUESTS, false, false, Set.of("event", "application", "entry", "tab", "request")),

    /**
     * A document its recipient made was published (MC1.1, ADR-0259). Facts: {@code event} (a stable key), and
     * {@code application} and {@code entry}, the address parts of the page that opens the document: the application it
     * was routed to when the organization has it, else Document Management's. No title and no number: the words are the
     * platform's own and name the object. The person also gets a mail of the same facts, which the same category
     * switches on or off for each channel.
     */
    DOCUMENT_PUBLISHED(Category.DOCUMENTS, false, false, Set.of("event", "application", "entry"),
            MailTemplate.DOCUMENT_PUBLISHED),

    /**
     * A document was made available to the recipient's plant (MC2A, ADR-0260): its creator published it to the plants
     * and the recipient belongs to the group of one of them. Facts: {@code event} (a stable key), and
     * {@code application} and {@code entry}, the address parts of the page that opens the document. No title, no
     * number, no plant name: the words are the platform's own. The person also gets a mail of the same facts, which the
     * same category switches on or off for each channel.
     */
    DOCUMENT_AT_PLANT(Category.DOCUMENTS, false, false, Set.of("event", "application", "entry"),
            MailTemplate.DOCUMENT_AT_PLANT),

    /**
     * A fill-in the recipient filled scored below the limit of its application (MC2B, ADR-0261): an Action plan is
     * needed. Facts: {@code event} (a stable key), and {@code application} and {@code entry}, the address parts of the
     * page that opens the fill-in. No score, no limit, no plant, no title: the words are the platform's own. The person
     * also gets a mail of the same facts, which the same category switches on or off for each channel.
     */
    ACTION_PLAN_NEEDED(Category.DOCUMENTS, false, false, Set.of("event", "application", "entry"),
            MailTemplate.ACTION_PLAN_NEEDED);

    /** What a person switches on or off (ADR-0156). */
    public enum Category {
        /** Tasks and decisions of approvals. */
        APPROVALS,
        /** Notices a workflow sends. */
        WORKFLOWS,
        /** Things about the account and the organization's standing; cannot be switched off. */
        ACCOUNT,
        /** Change requests on records a person asked for or owns. */
        REQUESTS,
        /** Documents a person made that were published. */
        DOCUMENTS
    }

    private final Category category;
    private final boolean floor;
    private final boolean writable;
    private final Set<String> facts;
    private final MailTemplate mail;

    NoticeKind(Category category, boolean floor, boolean writable, Set<String> facts) {
        this(category, floor, writable, facts, null);
    }

    NoticeKind(Category category, boolean floor, boolean writable, Set<String> facts, MailTemplate mail) {
        this.category = category;
        this.floor = floor;
        this.writable = writable;
        this.facts = Set.copyOf(facts);
        this.mail = mail;
    }

    /**
     * The mail that goes with a notice of this kind, built from the same facts (the notification module queues it when
     * the notice is made; whether it is sent is the person's own choice for the category). Empty for a kind that is
     * not mailed.
     */
    public Optional<MailTemplate> mail() {
        return Optional.ofNullable(mail);
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
