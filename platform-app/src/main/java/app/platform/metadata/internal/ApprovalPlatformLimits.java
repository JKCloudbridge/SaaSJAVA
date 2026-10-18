package app.platform.metadata.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two numbers of the approval runtime that the screens show next to the limits of a definition (ADR-0147,
 * ADR-0151):
 * the longest comment and the longest delegation. They are settings of the approval module
 * ({@code platform.approval.limits.*}); the metadata module reads them by name here, once, so that it needs no
 * dependency
 * on the module that runs approvals.
 */
@Component
class ApprovalPlatformLimits {

    private final int commentLength;
    private final int delegationDays;

    ApprovalPlatformLimits(@Value("${platform.approval.limits.comment-length:2000}") int commentLength,
            @Value("${platform.approval.limits.max-delegation-days:366}") int delegationDays) {
        this.commentLength = commentLength;
        this.delegationDays = delegationDays;
    }

    /** The longest comment, in characters. */
    int commentLength() {
        return commentLength;
    }

    /** The longest delegation, in days. */
    int delegationDays() {
        return delegationDays;
    }
}
