package app.platform.sharedkernel.notice;

/**
 * Creates in-app notices (Sprint 25, ADR-0154), for the members of the organization the thread works for. The same
 * pattern as {@code MailQueue}: a module that needs to tell a member something hands over a {@link NoticeRequest} and
 * carries on; the implementation lives in the {@code notification} module, so no business module depends on it.
 *
 * <p>The notice is written in the caller's transaction, in the organization's tenant context, so a rolled-back change
 * leaves no notice behind and a notice is never written for another organization. The member's preference is read
 * at creation: a notice that is switched off is not created at all (ADR-0156). Nobody writes the notice tables but the
 * implementation (an architecture test).
 */
public interface Notices {

    /** What became of a request. */
    enum Result {
        /** The notice was created. */
        CREATED,
        /** The same notice (same recipient and key) exists already; nothing was created. */
        DUPLICATE,
        /** The recipient switched this kind of notice off, or is not an active member; nothing was created. */
        SUPPRESSED
    }

    /**
     * Creates the notice unless it exists already or the recipient does not want it.
     *
     * @param request what happened and to whom
     * @return what became of it
     */
    Result notify(NoticeRequest request);
}
