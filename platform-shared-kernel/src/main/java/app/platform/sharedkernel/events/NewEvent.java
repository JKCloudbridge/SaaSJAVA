package app.platform.sharedkernel.events;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An event a module wants to announce. It becomes durable in the same database transaction as the change it
 * describes (see {@link EventPublisher}).
 *
 * <p>The type names what happened, in the past tense, as lower-case words separated by dots, for example
 * {@code tenant.suspended}. The payload is a JSON document. It must hold only what handlers need (identifiers and
 * facts), never secrets or personal data, because it is stored in the outbox and may appear in a dead-letter review.
 *
 * @param type what happened, for example {@code tenant.activated}
 * @param payload a JSON document, at most {@value #MAX_PAYLOAD_CHARACTERS} characters
 */
public record NewEvent(String type, String payload) {

    /** Largest accepted payload. Events carry identifiers, not documents: a big payload is a design mistake. */
    public static final int MAX_PAYLOAD_CHARACTERS = 65_536;

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final int MAX_TYPE_LENGTH = 100;

    public NewEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (type.length() > MAX_TYPE_LENGTH || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("An event type is lower-case words separated by dots, for example "
                    + "'tenant.suspended', at most " + MAX_TYPE_LENGTH + " characters");
        }
        if (payload.length() > MAX_PAYLOAD_CHARACTERS) {
            throw new IllegalArgumentException("An event payload is at most " + MAX_PAYLOAD_CHARACTERS
                    + " characters");
        }
    }
}
