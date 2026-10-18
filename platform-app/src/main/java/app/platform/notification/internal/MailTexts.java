package app.platform.notification.internal;

import app.platform.sharedkernel.notice.NoticeText;
import java.util.Map;

/**
 * The words of every mail the platform sends (English only for now), as plain text and HTML. Small on purpose: fixed
 * sentences with a few named places ({@code {{link}}}); no template engine, and no personal text at all, so nothing a
 * person typed can end up in a message from the platform. The HTML form escapes every value it inserts.
 */
final class MailTexts {

    /** The messages. */
    enum Kind {

        SIGN_UP_LINK(
                "Finish creating your account",
                """
                Hello,

                Someone asked to create an account with this e-mail address. To continue, open this link and choose \
                your name and a password:

                {{link}}

                The link works once and stops working in {{lifetime}}. If you did not ask for this, ignore this \
                message: nothing is created until the link is used.
                """),

        ACCOUNT_EXISTS(
                "You already have an account",
                """
                Hello,

                Someone asked to create an account with this e-mail address, but you already have one. You can sign \
                in here:

                {{signInLink}}

                If you forgot your password, you can set a new one here:

                {{forgotLink}}

                If it was not you who asked, you do not need to do anything.
                """),

        PASSWORD_RESET_LINK(
                "Reset your password",
                """
                Hello,

                Someone asked to reset the password of the account with this e-mail address. To choose a new \
                password, open this link:

                {{link}}

                The link works once and stops working in {{lifetime}}. If you did not ask for this, ignore this \
                message: your password stays as it is.
                """),

        PASSWORD_CHANGED(
                "Your password was changed",
                """
                Hello,

                The password of the account with this e-mail address was just changed with a reset link, and every \
                device was signed out.

                If this was you, you do not need to do anything. If it was not you, set a new password right away:

                {{forgotLink}}
                """),

        ACCOUNT_LOCKED(
                "Your account was locked for a short time",
                """
                Hello,

                There were several failed attempts to sign in to the account with this e-mail address, so it is \
                locked for a short time. It unlocks by itself.

                If this was you, wait a few minutes and try again, or set a new password:

                {{forgotLink}}

                If it was not you, someone may be trying to guess your password. Nothing was changed, and you can \
                set a new password to be safe:

                {{forgotLink}}
                """),

        INVITATION_NEW(
                "You have been invited to join an organization",
                """
                Hello,

                An administrator of the organization "{{organization}}" invited this e-mail address to join it. To \
                accept, open this link and choose your name and a password:

                {{link}}

                The link works once and stops working in {{lifetime}}. The invitation gives no access until you \
                accept it. If you do not know this organization or did not expect this message, ignore it: nothing \
                is created until the link is used.
                """),

        INVITATION_EXISTING(
                "You have been invited to join an organization",
                """
                Hello,

                An administrator of the organization "{{organization}}" invited this e-mail address, which already \
                has an account, to join it. To accept, open this link and sign in with that account:

                {{link}}

                The link works once and stops working in {{lifetime}}. The invitation gives no access until you \
                accept it. If you do not know this organization or did not expect this message, ignore it: nothing \
                changes until the link is used.
                """),

        FIRST_ADMINISTRATOR_NEW(
                "You have been invited to set up an organization",
                """
                Hello,

                You have been invited to set up and administer the organization "{{organization}}". To accept, open \
                this link and choose your name and a password:

                {{link}}

                The link works once and stops working in {{lifetime}}. The organization opens when you accept. If \
                you do not know this organization or did not expect this message, ignore it: nothing is created \
                until the link is used.
                """),

        RECORD_NOTICE(
                "A record needs your attention",
                """
                Hello,

                A workflow of the organization "{{organization}}" asks you to look at a record of {{objectLabel}}. \
                Open it here:

                {{link}}

                You need to sign in to see it, and what you see is what your access allows. If you do not know this \
                organization or did not expect this message, ignore it: nothing changes.
                """),

        APPROVAL_REQUEST(
                "A record needs your decision",
                """
                Hello,

                A record of {{objectLabel}} in the organization "{{organization}}" is waiting for your decision \
                in the approval "{{process}}". Open your approvals here:

                {{link}}

                You need to sign in. Nothing is decided by opening the link, and what you can see of the record is \
                what your access allows. If you do not know this organization or did not expect this message, ignore \
                it: nothing changes.
                """),

        APPROVAL_REMINDER(
                "A decision is still waiting for you",
                """
                Hello,

                A record of {{objectLabel}} in the organization "{{organization}}" has waited longer than expected \
                for your decision in the approval "{{process}}". Open your approvals here:

                {{link}}

                You need to sign in. Nothing is decided by opening the link, and what you can see of the record is \
                what your access allows. If you do not know this organization or did not expect this message, ignore \
                it: nothing changes.
                """),

        TRIAL_ENDING(
                "The trial of your organization is ending",
                """
                Hello,

                The trial of the organization "{{organization}}" has ended or is about to end. If no plan is active \
                in {{days}}, the organization is suspended: nobody can sign in to it, but nothing is deleted, and a \
                platform administrator can open it again once a plan is active.

                This message goes to the people who administer the organization.
                """),

        TRIAL_SUSPENDED(
                "Your organization is suspended",
                """
                Hello,

                The trial of the organization "{{organization}}" is over and the organization is suspended: nobody \
                can sign in to it. Nothing was deleted. A platform administrator can open it again once a plan is \
                active.

                This message goes to the people who administer the organization.
                """),

        FIRST_ADMINISTRATOR_EXISTING(
                "You have been invited to set up an organization",
                """
                Hello,

                You have been invited to set up and administer the organization "{{organization}}". This e-mail \
                address already has an account. To accept, open this link and sign in with that account:

                {{link}}

                The link works once and stops working in {{lifetime}}. The organization opens when you accept. If \
                you do not know this organization or did not expect this message, ignore it: nothing changes until \
                the link is used.
                """);

        private final String subject;
        private final String text;

        Kind(String subject, String text) {
            this.subject = subject;
            this.text = text;
        }

        String subject() {
            return subject;
        }
    }

    private static final String CUSTOM_FOOTER = "You need to sign in, and what you see is what your access allows. "
            + "If you do not know this organization or did not expect this message, ignore it: nothing changes.";

    private MailTexts() {
    }

    /**
     * The organization's name as it may appear in a mail: text an administrator chose, so it is cut to a short length
     * and reduced to letters, digits, spaces and a few quiet marks. Anything that could make a link or an address
     * (colon, slash, at-sign, dot) and anything that could break a line is dropped, so the name can only be a name.
     */
    static String safeName(String name) {
        return NoticeText.safeName(name);
    }

    /**
     * The plain-text body of a mail whose words an administrator wrote (Sprint 25, ADR-0155): their text, then the
     * platform's own link and footer. The text has passed {@link NoticeText#validate} when it was saved and has its
     * placeholders filled with cleaned labels, so it holds no link and no address of its own.
     */
    static String customText(String body, String link) {
        return "Hello,\n\n" + body + "\n\nOpen it here:\n\n" + link + "\n\n" + CUSTOM_FOOTER;
    }

    /** The HTML form of {@link #customText}: paragraphs, the link as a link, every inserted value escaped. */
    static String customHtml(String body, String link) {
        StringBuilder html = new StringBuilder("<!doctype html><html><body><p>Hello,</p>");
        for (String paragraph : body.strip().split("\n\n")) {
            html.append("<p>").append(escape(paragraph.strip()).replace("\n", "<br>")).append("</p>");
        }
        html.append("<p>Open it here:</p><p>").append(asLinkIfAddress(escape(link))).append("</p><p>")
                .append(escape(CUSTOM_FOOTER)).append("</p></body></html>");
        return html.toString();
    }

    /** The plain-text body with the values inserted as they are. */
    static String text(Kind kind, Map<String, String> values) {
        return fill(kind.text, values, false);
    }

    /** The HTML body: paragraphs, links as links, every inserted value escaped. */
    static String html(Kind kind, Map<String, String> values) {
        StringBuilder html = new StringBuilder("<!doctype html><html><body>");
        for (String paragraph : kind.text.strip().split("\n\n")) {
            String filled = fill(paragraph.strip(), values, true);
            html.append("<p>").append(filled.replace("\n", "<br>")).append("</p>");
        }
        return html.append("</body></html>").toString();
    }

    private static String fill(String template, Map<String, String> values, boolean html) {
        String result = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            String inserted = html ? asLinkIfAddress(escape(value.getValue())) : value.getValue();
            result = result.replace("{{" + value.getKey() + "}}", inserted);
        }
        return result;
    }

    /** A value that is an address of the platform becomes a clickable link; anything else stays text. */
    private static String asLinkIfAddress(String escaped) {
        return escaped.startsWith("http://") || escaped.startsWith("https://")
                ? "<a href=\"" + escaped + "\">" + escaped + "</a>" : escaped;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
