package app.platform.identity.internal;

import java.nio.CharBuffer;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Hashes and checks passwords with Argon2id (story S3-SEC-01, ADR-0020).
 *
 * <p>Every hash is a text that carries its own algorithm and parameters ({@code
 * $argon2id$v=19$m=...,t=...,p=...$salt$hash}), so the parameters can change later without breaking old hashes (story
 * S3-SEC-02): {@link #needsUpgrade(String)} says whether a stored hash is weaker than the current settings, and the
 * sign-in path then stores a fresh hash of the password it has just verified. A hash made by the older bcrypt
 * algorithm is still verified and always needs the upgrade; it exists so that the upgrade path is tested and so that
 * hashes imported from elsewhere can be accepted.
 *
 * <p>A hash costs memory and time on purpose. At most {@code maxConcurrentHashes} run at once on an instance and a
 * request that cannot get a slot within {@code hashWait} is refused with {@link HashingBusyException}; without that
 * bound a burst of sign-in attempts could use all of the memory of the instance.
 */
final class PasswordHasher {

    private static final String ARGON2ID_PREFIX = "$argon2id$";
    private static final Pattern PARAMETERS = Pattern.compile("^\\$argon2id\\$v=\\d+\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$");
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final Argon2PasswordEncoder argon2;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
    private final int memoryKib;
    private final int iterations;
    private final int parallelism;
    private final Semaphore slots;
    private final Duration wait;
    private final String dummyHash;

    PasswordHasher(IdentityProperties.Password settings) {
        this.memoryKib = settings.memoryKib();
        this.iterations = settings.iterations();
        this.parallelism = settings.parallelism();
        this.argon2 = new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, parallelism, memoryKib, iterations);
        this.slots = new Semaphore(settings.maxConcurrentHashes(), true);
        this.wait = settings.hashWait();
        // The hash an unknown account is "verified" against, so that the attempt costs the same as a real one. Made
        // with the current parameters, from a password nobody knows, once per start.
        char[] unknown = Hashes.randomSecret().toCharArray();
        this.dummyHash = hash(unknown);
        Arrays.fill(unknown, '\0');
    }

    /**
     * Hashes a password.
     *
     * @throws HashingBusyException when no hashing slot became free in time
     */
    String hash(char[] password) {
        return withSlot(() -> argon2.encode(normalized(password)));
    }

    /**
     * Checks a password against a stored hash.
     *
     * @throws HashingBusyException when no hashing slot became free in time
     */
    boolean matches(char[] password, String storedHash) {
        return withSlot(() -> {
            if (storedHash.startsWith(ARGON2ID_PREFIX)) {
                return argon2.matches(normalized(password), storedHash);
            }
            if (storedHash.startsWith("$2")) {
                try {
                    return bcrypt.matches(normalized(password), storedHash);
                } catch (IllegalArgumentException e) {
                    // The old algorithm cannot take more than 72 bytes: such a password cannot be the one it hashed.
                    return false;
                }
            }
            return false;
        });
    }

    /**
     * Does the same work as {@link #matches(char[], String)} against a hash nobody can match, so that a sign-in for an
     * account that does not exist (or may not sign in) takes as long as a real check. Always false.
     *
     * @throws HashingBusyException when no hashing slot became free in time
     */
    boolean matchesNothing(char[] password) {
        return withSlot(() -> {
            // The result is ignored on purpose: only the work matters.
            argon2.matches(normalized(password), dummyHash);
            return Boolean.FALSE;
        });
    }

    /** Whether the stored hash is weaker than, or different from, what a new hash would be today. */
    boolean needsUpgrade(String storedHash) {
        Matcher matcher = PARAMETERS.matcher(storedHash);
        if (!matcher.find()) {
            return true;
        }
        return Integer.parseInt(matcher.group(1)) != memoryKib
                || Integer.parseInt(matcher.group(2)) != iterations
                || Integer.parseInt(matcher.group(3)) != parallelism;
    }

    /** A hash made with the old algorithm, for tests of the upgrade path. */
    String legacyHashForTests(char[] password) {
        return bcrypt.encode(normalized(password));
    }

    /**
     * The password in Unicode compatibility form (NFKC), so that the same password typed on another keyboard or
     * produced by another input method hashes the same. Applied on every path that hashes or checks.
     */
    private static String normalized(char[] password) {
        return Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFKC);
    }

    private <T> T withSlot(java.util.function.Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HashingBusyException();
        }
        if (!acquired) {
            throw new HashingBusyException();
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }

    /** No hashing slot was free in time. The caller answers "try again later" and nothing about the account. */
    static final class HashingBusyException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        HashingBusyException() {
            super("No password hashing capacity is available");
        }
    }
}
