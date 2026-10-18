package app.platform.approval.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns what the rules of a step named into the people who are asked (ADR-0146, ADR-0151), as a pure function of facts:
 * no database, no clock. The facts are gathered by {@link ApproverResolver}; this class only decides.
 *
 * <p>The order of the decisions: a person named by several rules is one person; a person who is not an active member,
 * or
 * does not hold the licence their profile needs, or is the submitter (unless the process allows it), is left out with a
 * stable reason counted once; when nobody is left the fallback approver is tried the same way; then each person asked
 * who has a delegation in force is joined by their delegate (added beside them, never instead; a delegate's own
 * delegation is not followed, so there are no chains; a delegate must be eligible and is not asked twice).
 */
final class ApproverResolution {

    /** The reasons a person is left out (stable keys; they are the keys of the history). */
    static final String INACTIVE = "inactive";
    static final String UNLICENSED = "unlicensed";
    static final String SELF_APPROVAL = "self_approval_excluded";

    /**
     * A person a rule named, with the two facts that matter.
     *
     * @param userId the person
     * @param active whether they are an active member of the organization
     * @param licensed whether they hold the licence their profile needs
     */
    record Person(UUID userId, boolean active, boolean licensed) {
    }

    /**
     * How a person came to be asked.
     */
    enum Via { DIRECT, DELEGATED, FALLBACK }

    /**
     * One person who is asked.
     *
     * @param userId the person
     * @param via how
     * @param forUser for a delegate the person they act for, otherwise null
     */
    record Assignee(UUID userId, Via via, UUID forUser) {
    }

    /**
     * A delegation in force.
     *
     * @param delegator whose tasks the delegate is asked as well for
     * @param delegate who is asked as well
     */
    record Delegation(UUID delegator, UUID delegate) {
    }

    /**
     * The people asked.
     *
     * @param assignees who is asked, in a stable order
     * @param left why people were left out, a reason with how many
     * @param usedFallback whether the fallback approver was asked because the rules named nobody who can decide
     * @param dropped the people left out and why, in order (for the history)
     */
    record Resolved(List<Assignee> assignees, Map<String, Integer> left, boolean usedFallback,
            List<Dropped> dropped) {

        /** Copies the lists. */
        Resolved {
            assignees = List.copyOf(assignees);
            left = Map.copyOf(left);
            dropped = List.copyOf(dropped);
        }

        /** Whether nobody can decide. */
        boolean nobody() {
            return assignees.isEmpty();
        }
    }

    /**
     * One person who was left out.
     *
     * @param userId the person
     * @param reason why
     */
    record Dropped(UUID userId, String reason) {
    }

    private ApproverResolution() {
    }

    /**
     * Works out who is asked.
     *
     * @param ruleResults what each rule of the step named, in the order of the rules
     * @param fallback what the fallback approver named, or empty
     * @param submitter the person who submitted, or null when the platform did (nobody is then excluded)
     * @param allowSelf whether the submitter may be asked too
     * @param delegations the delegations in force for this process today
     * @param standing the standing of a person a delegation names, or null when they are not a member
     */
    static Resolved resolve(List<List<Person>> ruleResults, List<Person> fallback, UUID submitter, boolean allowSelf,
            List<Delegation> delegations, Function<UUID, Person> standing) {
        Map<String, Integer> left = new LinkedHashMap<>();
        List<Dropped> dropped = new ArrayList<>();
        List<Assignee> assignees = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        ask(ruleResults.stream().flatMap(List::stream).toList(), Via.DIRECT, submitter, allowSelf, seen, assignees,
                left, dropped);
        boolean usedFallback = false;
        if (assignees.isEmpty() && !fallback.isEmpty()) {
            ask(fallback, Via.FALLBACK, submitter, allowSelf, seen, assignees, left, dropped);
            usedFallback = !assignees.isEmpty();
        }
        List<Assignee> principals = List.copyOf(assignees);
        for (Assignee principal : principals) {
            for (Delegation delegation : delegations) {
                if (!delegation.delegator().equals(principal.userId())
                        || delegation.delegate().equals(principal.userId())
                        || assignees.stream().anyMatch(other -> other.userId().equals(delegation.delegate()))) {
                    continue;
                }
                Person delegate = standing.apply(delegation.delegate());
                if (delegate == null || !delegate.active() || !delegate.licensed()
                        || (!allowSelf && submitter != null && delegate.userId().equals(submitter))) {
                    continue;
                }
                assignees.add(new Assignee(delegate.userId(), Via.DELEGATED, principal.userId()));
            }
        }
        return new Resolved(assignees, left, usedFallback, dropped);
    }

    private static void ask(List<Person> named, Via via, UUID submitter, boolean allowSelf, Set<UUID> seen,
            List<Assignee> assignees, Map<String, Integer> left, List<Dropped> dropped) {
        for (Person person : named) {
            if (!seen.add(person.userId())) {
                continue;
            }
            String reason = null;
            if (!person.active()) {
                reason = INACTIVE;
            } else if (!person.licensed()) {
                reason = UNLICENSED;
            } else if (!allowSelf && submitter != null && person.userId().equals(submitter)) {
                reason = SELF_APPROVAL;
            }
            if (reason == null) {
                assignees.add(new Assignee(person.userId(), via, null));
            } else {
                left.merge(reason, 1, Integer::sum);
                dropped.add(new Dropped(person.userId(), reason));
            }
        }
    }
}
