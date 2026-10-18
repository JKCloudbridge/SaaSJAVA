package app.platform.approval.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the approval engine (ADR-0151, ADR-0152). The defaults suit a deployment.
 *
 * @param limits the bounds of what a person can type and make
 * @param inbox how big a page of tasks or requests is
 * @param mail whether a task is announced by mail
 */
@ConfigurationProperties("platform.approval")
record ApprovalProperties(@DefaultValue Limits limits, @DefaultValue Inbox inbox, @DefaultValue Mail mail) {

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
}
