package app.platform.notification.internal;

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

    private MailTexts() {
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
