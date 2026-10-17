package app.platform.metadata.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two numbers of the workflow runtime that a definition is checked against and that the screens show (ADR-0140,
 * ADR-0142): the most attempts of an action and the deepest chain of workflows. They are settings of the workflow
 * module ({@code platform.workflow.*}); the metadata module reads them by name here, once, so that it needs no
 * dependency on the module that runs workflows.
 */
@Component
class WorkflowPlatformLimits {

    private final int maxAttempts;
    private final int maxChainDepth;

    WorkflowPlatformLimits(@Value("${platform.workflow.retry.max-attempts:5}") int maxAttempts,
            @Value("${platform.workflow.limits.max-chain-depth:5}") int maxChainDepth) {
        this.maxAttempts = maxAttempts;
        this.maxChainDepth = maxChainDepth;
    }

    /** The most attempts of an action. */
    int maxAttempts() {
        return maxAttempts;
    }

    /** The deepest chain of workflows started one by another. */
    int maxChainDepth() {
        return maxChainDepth;
    }
}
