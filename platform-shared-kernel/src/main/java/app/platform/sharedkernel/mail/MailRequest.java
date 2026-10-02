package app.platform.sharedkernel.mail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A request to send one kind of e-mail to one address. It holds facts, never a finished message and never a secret: the
 * variable names are checked, and a name that contains a word for secret material (for example {@code token},
 * {@code password}) is refused when the request is built, so a mistake fails in a test instead of putting a secret into
 * the queue. Values are cut to a bounded length.
 *
 * @param template what happened
 * @param email the recipient, already normalized (lower case)
 * @param userId the user it is about, or null when there is none (a sign-up for an address without an account)
 * @param variables non-secret facts for the text (for example a display name)
 */
public record MailRequest(MailTemplate template, String email, UUID userId, Map<String, String> variables) {

    /** Largest stored variable value. */
    public static final int MAX_VALUE_LENGTH = 200;

    /** Most variables of one request. */
    public static final int MAX_VARIABLES = 10;

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Set<String> SECRET_WORDS = Set.of(
            "password", "passwd", "secret", "token", "credential", "authorization", "cookie", "verifier", "code",
            "link", "url");

    public MailRequest {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(email, "email");
        if (email.length() < 3 || email.length() > 254 || !email.equals(email.strip())) {
            throw new IllegalArgumentException("A mail request needs a normalized address");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (variables != null) {
            if (variables.size() > MAX_VARIABLES) {
                throw new IllegalArgumentException("A mail request has at most " + MAX_VARIABLES + " variables");
            }
            variables.forEach((key, value) -> {
                if (!KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("A mail variable name is a lower-case word with underscores");
                }
                for (String word : key.split("_")) {
                    if (SECRET_WORDS.contains(word)) {
                        throw new IllegalArgumentException("A mail request must not hold secret material or a link");
                    }
                }
                String text = value == null ? "" : value;
                copy.put(key, text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH) : text);
            });
        }
        variables = Map.copyOf(copy);
    }

    /** A request without a user and without variables. */
    public static MailRequest of(MailTemplate template, String email) {
        return new MailRequest(template, email, null, Map.of());
    }

    /** The same request about a user. */
    public MailRequest forUser(UUID id) {
        return new MailRequest(template, email, id, variables);
    }

    /** The same request with one more variable. */
    public MailRequest with(String key, String value) {
        Map<String, String> more = new LinkedHashMap<>(variables);
        more.put(key, value);
        return new MailRequest(template, email, userId, more);
    }

    @Override
    public String toString() {
        // The address is personal data: it stays out of any log line that prints a request.
        return "MailRequest[" + template + "]";
    }
}
