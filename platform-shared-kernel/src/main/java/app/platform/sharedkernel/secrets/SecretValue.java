package app.platform.sharedkernel.secrets;

import java.util.Arrays;
import java.util.Objects;

/**
 * A secret's plaintext. Deliberately not a record: {@link #toString()} must never reveal the value,
 * and there is no value-based {@code equals}/{@code hashCode} that could leak it into logs or maps.
 */
public final class SecretValue {

    private final char[] value;

    private SecretValue(char[] value) {
        this.value = value;
    }

    public static SecretValue of(CharSequence plaintext) {
        Objects.requireNonNull(plaintext, "plaintext");
        char[] copy = new char[plaintext.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = plaintext.charAt(i);
        }
        return new SecretValue(copy);
    }

    /** Returns a copy of the plaintext. Call only at the moment of use; never log or return it from an API. */
    public char[] reveal() {
        return Arrays.copyOf(value, value.length);
    }

    /** Overwrites the held plaintext. */
    public void destroy() {
        Arrays.fill(value, '\0');
    }

    @Override
    public String toString() {
        return "SecretValue[REDACTED]";
    }
}
