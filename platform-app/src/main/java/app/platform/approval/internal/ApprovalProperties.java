package app.platform.approval.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the approval engine (ADR-0151, ADR-0152). The defaults suit a deployment.
 *
 * @param limits the bounds of what a person can type and make
 * @param inbox how big a page of tasks or requests is
 * @param mail whether a task is announced by mail
 * @param timer the reminders and escalations of steps that wait (Sprint 25, ADR-0159)
 */
@ConfigurationProperties("platform.approval")
record ApprovalProperties(@DefaultValue Limits limits, @DefaultValue Inbox inbox, @DefaultValue Mail mail,
        @DefaultValue Timer timer) {

    /**
     * The bounds.
     *
     * @param commentLength the longest comment, in characters (default 2000; the database allows 2000)
     * @param maxDelegationsPerPerson current or future delegations one person may have (default 20)
     * @param maxDelegationDays the longest delegation, in days (default 366; the database allows 366)
     */
    record Limits(@DefaultValue("2000") int commentLength, @DefaultValue("20") int maxDelegationsPerPerson,
            @DefaultValue("366") int maxDelegationDays) {

        Limits {
            if (commentLength < 1 || commentLength > 2000 || maxDelegationsPerPerson < 1
                    || maxDelegationDays < 1 || maxDelegationDays > 366) {
                throw new IllegalArgumentException("platform.approval.limits.* is out of range");
            }
        }
    }

    /**
     * The pages.
     *
     * @param pageSize the page size when none is asked for (default 50)
     * @param maxPageSize the largest page a caller may ask for (default 200)
     */
    record Inbox(@DefaultValue("50") int pageSize, @DefaultValue("200") int maxPageSize) {

        Inbox {
            if (pageSize < 1 || maxPageSize < pageSize || maxPageSize > 500) {
                throw new IllegalArgumentException("platform.approval.inbox.* is out of range");
            }
        }
    }

    /**
     * The mail that tells an approver they have a task.
     *
     * @param enabled whether a task is announced by mail (default true); the inbox always shows it
     */
    record Mail(@DefaultValue("true") boolean enabled) {
    }

    /**
     * The timer that reminds, escalates and marks overdue (ADR-0159). Every instance may run one; they coordinate
     * through the database.
     *
     * @param enabled whether this instance runs the timer (default true)
     * @param pollInterval how often it looks for steps whose time has come (default 30 seconds)
     * @param batchSize how many steps one look claims (default 50)
     * @param lease how long a claimed step is invisible to other instances (default 2 minutes); the claim moves the
     *        step's next look by this much, so an instance that dies mid-way hands the step to another after it
     */
    record Timer(@DefaultValue("true") boolean enabled, @DefaultValue("30s") java.time.Duration pollInterval,
            @DefaultValue("50") int batchSize, @DefaultValue("2m") java.time.Duration lease) {

        Timer {
            if (batchSize < 1 || batchSize > 500 || pollInterval.compareTo(java.time.Duration.ofMillis(100)) < 0
                    || lease.compareTo(java.time.Duration.ofSeconds(10)) < 0) {
                throw new IllegalArgumentException("platform.approval.timer.* is out of range");
            }
        }
    }
}
