package app.platform.metadata;

import app.platform.sharedkernel.notice.NoticeKind;

/**
 * The text an administrator wrote for one kind of notice or mail (Sprint 25, ADR-0155). Published metadata: only a
 * published text is ever used to build a notice, a draft in a change set is invisible here.
 *
 * @param kind the kind of notice the text is for
 * @param title the title, one line
 * @param body the body, with placeholders in braces from the list of the kind
 * @param version the version of the row, for concurrent changes
 */
public record NotificationTemplate(NoticeKind kind, String title, String body, long version) {

    /** Prints the kind only: the text is written by a person (ADR-0012). */
    @Override
    public String toString() {
        return "NotificationTemplate[" + kind + "]";
    }
}
