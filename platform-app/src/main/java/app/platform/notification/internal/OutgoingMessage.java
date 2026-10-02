package app.platform.notification.internal;

/**
 * A finished message, built at send time and never stored. It may hold a link with a secret token, so its text form
 * shows nothing.
 *
 * @param to the recipient address
 * @param subject the subject line
 * @param text the plain-text body
 * @param html the HTML body
 */
record OutgoingMessage(String to, String subject, String text, String html) {

    @Override
    public String toString() {
        return "OutgoingMessage[redacted]";
    }
}
