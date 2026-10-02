package app.platform.notification.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends over SMTP with the framework's mail sender, configured through {@code spring.mail.*} (host, port, credentials
 * and the timeouts come from the environment). Plain text and HTML in one message, UTF-8. The subject and the
 * addresses are checked for line breaks first, so nothing a person typed can add a header.
 */
@Component
class SmtpMailTransport implements MailTransport {

    private final JavaMailSender sender;
    private final String from;

    SmtpMailTransport(JavaMailSender sender, MailProperties properties) {
        this.sender = sender;
        this.from = properties.senderAddress();
    }

    @Override
    public void send(OutgoingMessage message) {
        requireSingleLine(message.to());
        requireSingleLine(message.subject());
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            // Marks the message as machine-made, so auto-responders do not answer it.
            mime.setHeader("Auto-Submitted", "auto-generated");
            sender.send(mime);
        } catch (MessagingException | MailException e) {
            throw new MailTransportException(e);
        }
    }

    private static void requireSingleLine(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A header value must be a single line");
        }
    }
}
