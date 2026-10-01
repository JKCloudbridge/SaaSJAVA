package spike.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.password.PasswordEncoder;
import spike.auth.AuthenticationAttempt.PasswordAttempt;
import spike.auth.AuthenticationOutcome.Authenticated;
import spike.auth.AuthenticationOutcome.Reason;
import spike.auth.AuthenticationOutcome.Rejected;

/**
 * Local credentials with the protections the Sprint 3 security stories must deliver:
 * constant-work verification for unknown accounts, progressive lockout, hash upgrade on login.
 */
public class LocalCredentialsProvider implements PlatformAuthenticationProvider {

    public enum Status { ACTIVE, SUSPENDED }

    public static final class Account {
        private final String userId;
        private volatile String passwordHash;
        private volatile Status status = Status.ACTIVE;
        private volatile int failedAttempts;
        private volatile Instant lockedUntil = Instant.MIN;
        private volatile int securityVersion = 1;

        Account(String userId, String passwordHash) {
            this.userId = userId;
            this.passwordHash = passwordHash;
        }

        public String userId() {
            return userId;
        }

        public String passwordHash() {
            return passwordHash;
        }

        public int failedAttempts() {
            return failedAttempts;
        }

        public Instant lockedUntil() {
            return lockedUntil;
        }

        public int securityVersion() {
            return securityVersion;
        }

        /** Suspends the account and invalidates all its outstanding tokens. */
        public void suspend() {
            status = Status.SUSPENDED;
            securityVersion++;
        }
    }

    static final int LOCK_AFTER_FAILURES = 5;
    static final Duration BASE_LOCK = Duration.ofMinutes(1);
    static final Duration MAX_LOCK = Duration.ofMinutes(15);

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String dummyHash;

    public LocalCredentialsProvider(PasswordEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
        this.dummyHash = encoder.encode("unused-dummy-password");
    }

    public Account register(String identifier, String rawPassword) {
        Account account = new Account("user-" + accounts.size(), encoder.encode(rawPassword));
        accounts.put(normalise(identifier), account);
        return account;
    }

    public Account find(String identifier) {
        return accounts.get(normalise(identifier));
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public boolean supports(AuthenticationAttempt attempt) {
        return attempt instanceof PasswordAttempt;
    }

    @Override
    public AuthenticationOutcome authenticate(AuthenticationAttempt attempt) {
        PasswordAttempt in = (PasswordAttempt) attempt;
        Account account = accounts.get(normalise(in.identifier()));
        if (account == null) {
            encoder.matches(in.password(), dummyHash); // same cost as a real check
            return new Rejected(Reason.UNKNOWN_ACCOUNT);
        }
        Instant now = clock.instant();
        boolean matches = encoder.matches(in.password(), account.passwordHash); // always pay the cost
        if (account.status != Status.ACTIVE) {
            return new Rejected(Reason.DISABLED);
        }
        if (now.isBefore(account.lockedUntil)) {
            return new Rejected(Reason.LOCKED);
        }
        if (!matches) {
            recordFailure(account, now);
            return new Rejected(Reason.BAD_CREDENTIALS);
        }
        account.failedAttempts = 0;
        if (encoder.upgradeEncoding(account.passwordHash)) {
            account.passwordHash = encoder.encode(in.password());
        }
        return new Authenticated(account.userId, account.securityVersion);
    }

    /** Test hook: installs a pre-computed hash, for example one produced by an older algorithm. */
    public void overrideHash(String identifier, String hash) {
        accounts.get(normalise(identifier)).passwordHash = hash;
    }

    private void recordFailure(Account account, Instant now) {
        int failures = ++account.failedAttempts;
        if (failures >= LOCK_AFTER_FAILURES) {
            long steps = failures - LOCK_AFTER_FAILURES;
            Duration lock = BASE_LOCK.multipliedBy(1L << Math.min(steps, 10));
            account.lockedUntil = now.plus(lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock);
        }
    }

    private static String normalise(String identifier) {
        return identifier.strip().toLowerCase(Locale.ROOT);
    }
}
