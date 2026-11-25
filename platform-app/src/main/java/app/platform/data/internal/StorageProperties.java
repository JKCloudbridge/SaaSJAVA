package app.platform.data.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the bytes of files are kept: an S3-compatible object store (Sprint 17, ADR-0120). The credentials come from the
 * environment of the deployment, never from code, a table or a log; they never print.
 *
 * <p><strong>Two ways to be known to the store (Sprint 33, ADR-0290).</strong> {@code keys} (the default, and what a
 * developer machine and a self-hosted store use): an access key and its secret are given. {@code role}: neither is
 * given and the platform the application runs on supplies short-lived credentials to the process (a role attached to
 * the container), found by the library's standard lookup. The two never mix: a key with {@code role}, or a bucket with
 * {@code keys} and no key, stops the start with a message that says which, so a forgotten secret cannot silently turn
 * into "use whatever role is there".
 *
 * @param endpoint the address of the store, or empty for the provider's own address of the region
 * @param region the region the store is in (an S3-compatible store that has none accepts any)
 * @param bucket the bucket every file of every organization is kept in, under a key that starts with the organization
 * @param accessKey the key that identifies the platform to the store ({@code keys} only)
 * @param secretKey the secret that goes with it ({@code keys} only)
 * @param pathStyle whether the bucket is named in the path ({@code endpoint/bucket/key}) and not in the host name,
 *        which self-hosted stores need
 * @param socketTimeout how long a read from the store may wait for the next bytes (default 60 seconds)
 * @param callTimeout how long one call to the store may take in all (default 3 minutes)
 * @param credentials {@code keys} (default) or {@code role}
 */
@ConfigurationProperties("platform.storage")
record StorageProperties(@DefaultValue("") String endpoint, @DefaultValue("us-east-1") String region,
        @DefaultValue("") String bucket, @DefaultValue("") String accessKey, @DefaultValue("") String secretKey,
        @DefaultValue("false") boolean pathStyle, @DefaultValue("60s") java.time.Duration socketTimeout,
        @DefaultValue("3m") java.time.Duration callTimeout, @DefaultValue("keys") String credentials) {

    static final String KEYS = "keys";
    static final String ROLE = "role";

    StorageProperties {
        if (!KEYS.equals(credentials) && !ROLE.equals(credentials)) {
            throw new IllegalArgumentException("platform.storage.credentials must be 'keys' or 'role'");
        }
        boolean anyKey = !accessKey.isBlank() || !secretKey.isBlank();
        boolean bothKeys = !accessKey.isBlank() && !secretKey.isBlank();
        if (ROLE.equals(credentials) && anyKey) {
            throw new IllegalArgumentException(
                    "platform.storage.credentials=role takes no access key and no secret key; remove them");
        }
        if (KEYS.equals(credentials) && !bucket.isBlank() && !bothKeys) {
            throw new IllegalArgumentException("platform.storage.bucket is set, so both platform.storage.access-key "
                    + "and platform.storage.secret-key are needed (or platform.storage.credentials=role)");
        }
    }

    /** Whether the credentials come from the platform the process runs on and not from a key and a secret. */
    boolean usesRole() {
        return ROLE.equals(credentials);
    }

    /** Whether a store is configured: files are refused (a clean 503) when it is not. */
    boolean configured() {
        return !bucket.isBlank() && (usesRole() || (!accessKey.isBlank() && !secretKey.isBlank()));
    }

    @Override
    public String toString() {
        return "StorageProperties[redacted]";
    }
}
