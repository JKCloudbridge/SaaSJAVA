package app.platform.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An approval process of an object, as published (Sprint 24, ADR-0145): a record that meets the entry criteria can be
 * submitted, the steps decide it in order, and the outcomes set fields of the record when it ends. The definition is
 * closed data; no part of it is run as code. A process is identified by its object and API name; the definition belongs
 * to the version it was made in, and a request names that version, so a later publication never changes a request under
 * way.
 *
 * @param id the identifier of the process (the same for every version)
 * @param versionId the identifier of this definition version
 * @param objectApiName the object it belongs to
 * @param apiName the permanent name, ending in {@code __c}
 * @param label the label people read
 * @param description what the process is for
 * @param active whether new requests may start under it
 * @param definitionVersion the number of this definition (1, 2, ...)
 * @param entryCriteria the entry criteria in the expression language, or null: it must give true or false and is judged
 *        on the record as it is when the request is submitted
 * @param allowSelfApproval whether a person who submits may also be asked to decide
 * @param steps the steps in order
 * @param outcomes what the end of a request sets on the record
 * @param fallback who is asked when a step names nobody who can decide, or null
 * @param version the version of the process row, for concurrent changes
 */
public record ApprovalProcessDefinition(UUID id, UUID versionId, String objectApiName, String apiName, String label,
        String description, boolean active, int definitionVersion, String entryCriteria, boolean allowSelfApproval,
        List<Step> steps, Outcomes outcomes, Approver fallback, long version) {

    /** Copies the list. */
    public ApprovalProcessDefinition {
        steps = List.copyOf(steps);
    }

    /** What a rule names, so a screen can offer the right choices. */
    public enum Names {
        /** Nothing: the rule has no value. */
        NOTHING,
        /** A user (a member's identifier). */
        USER,
        /** A profile. */
        PROFILE,
        /** A role. */
        ROLE,
        /** A public group. */
        GROUP,
        /** A user field of the object. */
        USER_FIELD
    }

    /** The kinds of approver rule (ADR-0146). */
    public enum ApproverKind {
        /** A specific person. */
        USER(Names.USER, "A specific person."),
        /** The active members who hold a profile. */
        PROFILE(Names.PROFILE, "Everyone who holds a profile."),
        /** The active members placed in a role. */
        ROLE(Names.ROLE, "Everyone placed in a role."),
        /** The active members of a public group, nesting included. */
        GROUP(Names.GROUP, "Everyone in a public group, including the groups inside it."),
        /** The owner of the record. */
        OWNER(Names.NOTHING, "The owner of the record."),
        /** The person a user field of the record names. */
        RECORD_USER(Names.USER_FIELD, "The person a user field of the record names, for example a manager field."),
        /** The members placed in the parent role of the role of the owner of the record. */
        OWNER_ROLE_MANAGER(Names.NOTHING, "The people placed in the role above the role of the record's owner.");

        private final Names names;
        private final String summary;

        ApproverKind(Names names, String summary) {
            this.names = names;
            this.summary = summary;
        }

        /** What the value of a rule of this kind names. */
        public Names names() {
            return names;
        }

        /** Who it asks, in words. */
        public String summary() {
            return summary;
        }

        /** The kind with the stored code, or empty for one this release does not accept. */
        public static Optional<ApproverKind> fromCode(String code) {
            for (ApproverKind kind : values()) {
                if (kind.name().equals(code)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** How many of the people asked must approve for a step to be complete. */
    public enum Completion {
        /** One approval completes the step. */
        ANY("One approval completes the step."),
        /** Every person asked must approve. */
        ALL("Every person asked must approve.");

        private final String summary;

        Completion(String summary) {
            this.summary = summary;
        }

        /** What it means, in words. */
        public String summary() {
            return summary;
        }

        /** The completion rule with the stored code, or empty. */
        public static Optional<Completion> fromCode(String code) {
            for (Completion completion : values()) {
                if (completion.name().equals(code)) {
                    return Optional.of(completion);
                }
            }
            return Optional.empty();
        }
    }

    /** How a request ended: the outcomes that may set fields of the record. */
    public enum Outcome {
        /** The last step was approved. */
        APPROVED("The last step was approved."),
        /** A person rejected a step. */
        REJECTED("A person rejected a step."),
        /** The request was withdrawn. */
        RECALLED("The request was withdrawn.");

        private final String summary;

        Outcome(String summary) {
            this.summary = summary;
        }

        /** When it happens, in words. */
        public String summary() {
            return summary;
        }

        /** The outcome with the stored code, or empty. */
        public static Optional<Outcome> fromCode(String code) {
            for (Outcome outcome : values()) {
                if (outcome.name().equals(code)) {
                    return Optional.of(outcome);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * One rule that names who is asked.
     *
     * @param kind the kind
     * @param value the one setting of the kind, or null
     */
    public record Approver(ApproverKind kind, String value) {

        /** Prints the kind only: the value names a person, a profile or a field. */
        @Override
        public String toString() {
            return "Approver[" + kind + "]";
        }
    }

    /**
     * One step.
     *
     * @param name the name of the step in the process
     * @param label the label people read
     * @param completion how many of the people asked must approve
     * @param approvers who is asked
     */
    public record Step(String name, String label, Completion completion, List<Approver> approvers) {

        /** Copies the list. */
        public Step {
            approvers = List.copyOf(approvers);
        }

        @Override
        public String toString() {
            return "Step[" + name + "]";
        }
    }

    /**
     * One field an outcome sets, to a literal written as a field default is written.
     *
     * @param field the field
     * @param value the literal
     */
    public record Assignment(String field, String value) {

        /** Prints the field name only: a literal is text a person typed. */
        @Override
        public String toString() {
            return "Assignment[" + field + "]";
        }
    }

    /**
     * What the end of a request sets on the record.
     *
     * @param approved set when the last step is approved
     * @param rejected set when a step is rejected
     * @param recalled set when the request is withdrawn
     */
    public record Outcomes(List<Assignment> approved, List<Assignment> rejected, List<Assignment> recalled) {

        /** Copies the lists. */
        public Outcomes {
            approved = List.copyOf(approved);
            rejected = List.copyOf(rejected);
            recalled = List.copyOf(recalled);
        }

        /** The updates of one outcome. */
        public List<Assignment> of(Outcome outcome) {
            return switch (outcome) {
                case APPROVED -> approved;
                case REJECTED -> rejected;
                case RECALLED -> recalled;
            };
        }

        /** No outcome sets anything. */
        public static Outcomes none() {
            return new Outcomes(List.of(), List.of(), List.of());
        }
    }

    /** Prints the identity only: labels, criteria and values are text a person typed. */
    @Override
    public String toString() {
        return "ApprovalProcessDefinition[" + objectApiName + "." + apiName + " v" + definitionVersion + "]";
    }
}
