package app.platform.secrets.internal;

import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.secrets.internal.SecretTables.ClosedHour;
import app.platform.secrets.internal.SecretTables.Entry;
import app.platform.secrets.internal.SecretTables.StoredVersion;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.secrets.CredentialUsage;
import app.platform.sharedkernel.secrets.SecretMetadata;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretStoreException;
import app.platform.sharedkernel.secrets.SecretStoreException.Reason;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The secrets store of the platform (Sprint 27, ADR-0212 to ADR-0214): envelope encryption in the platform's own
 * database, per organization, with versions.
 *
 * <p>Every call names its organization in the address ({@link SecretRef#scope()}), and the store works as that
 * organization: it opens the tenant context itself when the thread has none (a connection test resolves on a worker
 * thread), and refuses an address that names another organization than the one the thread already works for. Two
 * layers keep organizations apart: the row level security of the tables, and the address that is part of what every
 * ciphertext is authenticated against. The platform's own scope is not supported by this store (nothing needs it).
 *
 * <p>Writes join the caller's transaction, so a change of a secret, its audit record and the marking of the
 * connections that use it succeed or fail together. A resolve runs in a transaction of its own: its counts survive
 * whatever the caller does next, and it never delays the caller's commit.
 *
 * <p>Nothing here logs. The audit and the metrics carry identifiers, version numbers, reason keys and counts: never a
 * value, a ciphertext, a key, or the name an organization chose.
 */
@Service
final class DatabaseSecretsStore implements SecretsStore {

    /** What a write is for; the audit record and the guard against an empty rollback follow from it. */
    private enum Kind { PUT, ROLLBACK }

    /** An unexpected failure of the store. Its message is fixed; the cause is not repeated. */
    static final class SecretFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SecretFailure(Throwable cause) {
            super("The secrets store failed unexpectedly: " + cause.getClass().getName(), null, false, false);
        }
    }

    private static final String SCOPE_PREFIX = "tenant:";
    private static final int USAGE_KEEP_DAYS = 35;

    private final SecretTables tables;
    private final KeyEncryptionKeys keys;
    private final EnvelopeCipher cipher;
    private final SecretsProperties properties;
    private final TenantContexts contexts;
    private final AuditRecorder audit;
    private final ObjectProvider<CredentialUsage> usage;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final TransactionTemplate joining;
    private final TransactionTemplate separate;

    DatabaseSecretsStore(SecretTables tables, KeyEncryptionKeys keys, EnvelopeCipher cipher,
            SecretsProperties properties, TenantContexts contexts, AuditRecorder audit,
            ObjectProvider<CredentialUsage> usage, ErrorTracker tracker, MeterRegistry meters,
            PlatformTransactionManager transactions) {
        this.tables = tables;
        this.keys = keys;
        this.cipher = cipher;
        this.properties = properties;
        this.contexts = contexts;
        this.audit = audit;
        this.usage = usage;
        this.tracker = tracker;
        this.meters = meters;
        this.joining = new TransactionTemplate(transactions);
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // -- the contract -------------------------------------------------------------------------------------------

    @Override
    public Optional<SecretValue> resolve(SecretRef ref) {
        TenantId tenant = tenantOf(ref);
        try {
            Optional<SecretValue> found = inTenant(tenant, () -> separate.execute(status -> read(tenant, ref.name())));
            count("resolve", found.isPresent() ? "found" : "missing");
            return found;
        } catch (SecretStoreException failure) {
            count("resolve", failure.reason().key());
            if (failure.reason() == Reason.KEY_UNAVAILABLE || failure.reason() == Reason.UNREADABLE) {
                noteFailure(tenant, ref.name(), failure.reason());
            }
            throw failure;
        } catch (RuntimeException unexpected) {
            throw unexpected(unexpected, "resolve");
        }
    }

    @Override
    public SecretMetadata put(SecretRef ref, SecretValue value) {
        TenantId tenant = tenantOf(ref);
        byte[] plain = encode(value);
        try {
            return inTenant(tenant, () -> joining.execute(status -> write(tenant, ref, plain, Kind.PUT)));
        } catch (SecretStoreException failure) {
            count("put", failure.reason().key());
            throw failure;
        } catch (RuntimeException unexpected) {
            throw unexpected(unexpected, "put");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public Optional<SecretMetadata> describe(SecretRef ref) {
        TenantId tenant = tenantOf(ref);
        try {
            return inTenant(tenant, () -> joining.execute(status -> tables.summary(ref.name())
                    .map(summary -> new SecretMetadata(ref, summary.version(), summary.createdAt(),
                            summary.updatedAt()))));
        } catch (SecretStoreException failure) {
            throw failure;
        } catch (RuntimeException unexpected) {
            throw unexpected(unexpected, "describe");
        }
    }

    @Override
    public void delete(SecretRef ref) {
        TenantId tenant = tenantOf(ref);
        try {
            inTenant(tenant, () -> joining.execute(status -> {
                erase(tenant, ref.name());
                return Boolean.TRUE;
            }));
        } catch (SecretStoreException failure) {
            count("delete", failure.reason().key());
            throw failure;
        } catch (RuntimeException unexpected) {
            throw unexpected(unexpected, "delete");
        }
    }

    // -- beyond the contract: what the credential endpoints need ---------------------------------------------------

    /**
     * Makes the version before the current one the current value again, as a new version (so the history only moves
     * forward). Empty when there is nothing to go back to. The value is read and sealed again on the server; no one is
     * shown it.
     */
    Optional<SecretMetadata> rollback(SecretRef ref) {
        TenantId tenant = tenantOf(ref);
        try {
            return inTenant(tenant, () -> joining.execute(status -> goBack(tenant, ref)));
        } catch (SecretStoreException failure) {
            count("rollback", failure.reason().key());
            throw failure;
        } catch (RuntimeException unexpected) {
            throw unexpected(unexpected, "rollback");
        }
    }

    // -- work done inside a transaction ---------------------------------------------------------------------------

    private Optional<SecretValue> read(TenantId tenant, String name) {
        Optional<Entry> entry = tables.find(name);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Optional<StoredVersion> stored = tables.version(entry.get().id(), entry.get().currentVersion());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        StoredVersion version = stored.get();
        EnvelopeCipher.Binding binding = binding(tenant, name, version.ordinal());
        byte[] plain = open(version, binding);
        try {
            UUID actor = actor();
            if (!version.keyId().equals(keys.active().id())) {
                rewrap(version, binding, actor);
            }
            noteUse(tenant, entry.get(), version, actor);
            return Optional.of(decode(plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private SecretMetadata write(TenantId tenant, SecretRef ref, byte[] plain, Kind kind) {
        if (plain.length == 0) {
            throw new SecretStoreException(Reason.VALUE_REJECTED);
        }
        UUID actor = actor();
        Optional<Entry> existing = tables.lock(ref.name());
        UUID entryId;
        int ordinal;
        if (existing.isPresent()) {
            entryId = existing.get().id();
            ordinal = existing.get().currentVersion() + 1;
        } else {
            if (tables.count() >= properties.limits().maxPerOrganization()) {
                throw new SecretStoreException(Reason.LIMIT_REACHED);
            }
            entryId = tables.insertEntry(ref.name(), actor);
            ordinal = 1;
        }
        EnvelopeCipher.Sealed sealed = seal(binding(tenant, ref.name(), ordinal), plain);
        tables.insertVersion(entryId, ordinal, sealed, actor);
        if (existing.isPresent()) {
            tables.setCurrent(entryId, ordinal, actor);
            int keep = properties.limits().retainedVersions();
            if (ordinal - keep >= 1) {
                tables.prune(entryId, ordinal - keep);
            }
            usageOf(ref.name()).voidTests();
        }
        String type = kind == Kind.ROLLBACK ? "credentials.rolled_back"
                : existing.isPresent() ? "credentials.rotated" : "credentials.created";
        audit.record(AuditRecord.of(type, AuditOutcome.SUCCESS).forUser(actorUser()).inTenant(tenant)
                .with("entry", entryId.toString()).with("version", Integer.toString(ordinal)));
        count(kind == Kind.ROLLBACK ? "rollback" : "put", "ok");
        SecretTables.Summary summary = tables.summary(ref.name()).orElseThrow();
        return new SecretMetadata(ref, summary.version(), summary.createdAt(), summary.updatedAt());
    }

    private Optional<SecretMetadata> goBack(TenantId tenant, SecretRef ref) {
        Optional<Entry> entry = tables.lock(ref.name());
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Optional<StoredVersion> previous = tables.previous(entry.get().id(), entry.get().currentVersion());
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        byte[] plain = open(previous.get(), binding(tenant, ref.name(), previous.get().ordinal()));
        try {
            return Optional.of(write(tenant, ref, plain, Kind.ROLLBACK));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private void erase(TenantId tenant, String name) {
        Optional<Entry> entry = tables.lock(name);
        if (entry.isEmpty()) {
            return;
        }
        tables.deleteEntry(entry.get().id());
        usageOf(name).voidTests();
        audit.record(AuditRecord.of("credentials.deleted", AuditOutcome.SUCCESS).forUser(actorUser())
                .inTenant(tenant).with("entry", entry.get().id().toString()));
        count("delete", "ok");
    }

    /** First use of a version is audited once; every use is counted; closed hours are written to the trail once. */
    private void noteUse(TenantId tenant, Entry entry, StoredVersion version, UUID actor) {
        if (tables.markFirstUse(version.id(), actor)) {
            audit.record(AuditRecord.of("credentials.first_used", AuditOutcome.SUCCESS).forUser(actorUser())
                    .inTenant(tenant).with("entry", entry.id().toString())
                    .with("version", Integer.toString(version.ordinal())));
        }
        if (tables.countUse(entry.id(), actor)) {
            for (ClosedHour hour : tables.closeHours(entry.id(), actor)) {
                audit.record(AuditRecord.of("credentials.use_summary", AuditOutcome.SUCCESS).inTenant(tenant)
                        .with("entry", entry.id().toString()).with("hour", hour.hourStart().toString())
                        .with("uses", Integer.toString(hour.uses()))
                        .with("failures", Integer.toString(hour.failures())));
            }
            tables.forgetOldHours(entry.id(), USAGE_KEEP_DAYS);
        }
    }

    /** The first failure of a secret in an hour is audited; the rest of the hour is only counted. */
    private void noteFailure(TenantId tenant, String name, Reason reason) {
        try {
            inTenant(tenant, () -> separate.execute(status -> {
                tables.find(name).ifPresent(entry -> {
                    if (tables.countFailure(entry.id(), actor()) == 1) {
                        audit.record(AuditRecord.of("credentials.resolve_failed", AuditOutcome.FAILURE)
                                .forUser(actorUser()).inTenant(tenant).because(reason.key())
                                .with("entry", entry.id().toString()));
                    }
                });
                return Boolean.TRUE;
            }));
        } catch (RuntimeException ignored) {
            // The failure that matters is the one being thrown to the caller; counting it must not replace it.
            count("resolve", "failure_not_counted");
        }
    }

    // -- cryptography ---------------------------------------------------------------------------------------------

    private EnvelopeCipher.Sealed seal(EnvelopeCipher.Binding binding, byte[] plain) {
        try {
            return cipher.seal(keys.active(), binding, plain);
        } catch (EnvelopeCipher.SealException failed) {
            throw new SecretStoreException(Reason.STORAGE_FAILED);
        }
    }

    private byte[] open(StoredVersion version, EnvelopeCipher.Binding binding) {
        KeyEncryptionKeys.Key kek = keys.find(version.keyId())
                .orElseThrow(() -> new SecretStoreException(Reason.KEY_UNAVAILABLE));
        try {
            return cipher.open(kek, binding, version.wrappedKey(), version.sealedValue());
        } catch (EnvelopeCipher.SealException failed) {
            throw new SecretStoreException(Reason.UNREADABLE);
        }
    }

    private void rewrap(StoredVersion version, EnvelopeCipher.Binding binding, UUID actor) {
        KeyEncryptionKeys.Key from = keys.find(version.keyId()).orElseThrow();
        try {
            byte[] wrapped = cipher.rewrap(from, keys.active(), binding, version.wrappedKey());
            tables.rewrap(version.id(), keys.active().id(), wrapped, actor);
        } catch (EnvelopeCipher.SealException failed) {
            throw new SecretStoreException(Reason.UNREADABLE);
        }
    }

    // -- small things ---------------------------------------------------------------------------------------------

    private byte[] encode(SecretValue value) {
        char[] chars = value.reveal();
        try {
            if (chars.length == 0 || chars.length > properties.limits().maxValueChars()) {
                throw new SecretStoreException(Reason.VALUE_REJECTED);
            }
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Arrays.fill(buffer.array(), (byte) 0);
            return bytes;
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private static SecretValue decode(byte[] bytes) {
        CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        try {
            return SecretValue.of(chars);
        } finally {
            Arrays.fill(chars.array(), '\0');
        }
    }

    private static EnvelopeCipher.Binding binding(TenantId tenant, String name, int ordinal) {
        return new EnvelopeCipher.Binding(SCOPE_PREFIX + tenant, name, ordinal);
    }

    /** The organization an address names; the platform's own scope and anything malformed are refused. */
    private static TenantId tenantOf(SecretRef ref) {
        if (!ref.scope().startsWith(SCOPE_PREFIX)) {
            throw new SecretStoreException(Reason.UNSUPPORTED_SCOPE);
        }
        try {
            return new TenantId(UUID.fromString(ref.scope().substring(SCOPE_PREFIX.length())));
        } catch (IllegalArgumentException malformed) {
            throw new SecretStoreException(Reason.UNSUPPORTED_SCOPE);
        }
    }

    private <T> T inTenant(TenantId tenant, Supplier<T> work) {
        Optional<TenantContext> current = contexts.current();
        if (current.isPresent()) {
            if (!current.get().tenantId().equals(tenant)) {
                throw new SecretStoreException(Reason.SCOPE_MISMATCH);
            }
            return work.get();
        }
        return contexts.call(TenantContext.of(tenant), work);
    }

    private UUID actorUser() {
        return contexts.current().flatMap(TenantContext::user).orElse(null);
    }

    private UUID actor() {
        UUID user = actorUser();
        return user == null ? ActorId.SYSTEM.value() : user;
    }

    /** The users of a credential, asked in the transaction of the change (none is also a valid answer). */
    private Users usageOf(String name) {
        return new Users(usage.orderedStream().toList(), name);
    }

    private record Users(java.util.List<CredentialUsage> sources, String name) {

        void voidTests() {
            sources.forEach(source -> source.voidTests(name));
        }
    }

    private SecretStoreException unexpected(RuntimeException cause, String operation) {
        tracker.track(ErrorReport.current(new SecretFailure(cause), "INTERNAL", "secrets." + operation));
        count(operation, Reason.STORAGE_FAILED.key());
        return new SecretStoreException(Reason.STORAGE_FAILED);
    }

    private void count(String operation, String result) {
        meters.counter("platform.secrets.operations", "operation", operation, "result", result).increment();
    }
}
