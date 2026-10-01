package app.platform.identity.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules a new password must meet (story S3-SEC-03, ADR-0020): a minimum length, a maximum length that keeps
 * hashing bounded, no known common or breached password, not built from the user's own address. There are
 * deliberately no rules about upper case, digits or symbols ("composition theatre"): they make people choose
 * predictable passwords without making them stronger. The rule "not the current password" needs the stored hash and
 * is applied by the caller.
 *
 * <p>The messages are written for the person choosing the password and say what to change. They never repeat the
 * password.
 */
final class PasswordPolicy {

    private final int minLength;
    private final int maxLength;
    private final BreachedPasswords breached;

    PasswordPolicy(IdentityProperties.Password settings, BreachedPasswords breached) {
        this.minLength = settings.minLength();
        this.maxLength = settings.maxLength();
        this.breached = breached;
    }

    /** The shortest accepted password. */
    int minLength() {
        return minLength;
    }

    /** The longest accepted password. */
    int maxLength() {
        return maxLength;
    }

    /**
     * Checks a candidate.
     *
     * @param candidate the new password
     * @param email the user's address, or null when not known
     * @return the problems found, empty when the password is acceptable
     */
    List<String> violations(char[] candidate, String email) {
        List<String> problems = new ArrayList<>();
        int length = new String(candidate).codePointCount(0, candidate.length);
        if (length < minLength) {
            problems.add("Must have at least " + minLength + " characters.");
        }
        if (length > maxLength) {
            problems.add("Must have at most " + maxLength + " characters.");
        }
        if (length >= 1 && length <= maxLength) {
            String text = new String(candidate);
            if (breached.contains(text)) {
                problems.add("Is a very common password. Choose something that is not on lists of common passwords.");
            }
            if (containsLocalPart(text, email)) {
                problems.add("Must not contain your email address.");
            }
        }
        return problems;
    }

    private static boolean containsLocalPart(String password, String email) {
        if (email == null) {
            return false;
        }
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        return local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local.toLowerCase(Locale.ROOT));
    }
}
