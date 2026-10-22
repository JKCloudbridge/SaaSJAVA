package app.platform.approval.internal;

/**
 * A hook of a business module failed in a way it did not mean to (FA4A, ADR-0187). The engine turns the hook's
 * exception into this one so that nothing the hook's exception says can reach a response, a log or the error tracking:
 * it carries no message and no cause. The type name of the original, which cannot hold data, is logged by the caller.
 * Whatever the call did is rolled back with the rest of the transaction; the person sees a plain failure.
 */
final class ApprovalHookFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ApprovalHookFailure() {
        super("An approval hook failed", null, false, false);
    }
}
