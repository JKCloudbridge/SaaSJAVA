package app.platform.data.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the bytes of files are kept: an S3-compatible object store (Sprint 17, ADR-0120). The credentials come from the
 * environment of the deployment, never from code, a table or a log; they never print.
 *
 * @param endpoint the address of the store, or empty for the provider's own address of the region
 * @param region the region the store is in (an S3-compatible store that has none accepts any)
 * @param bucket the bucket every file of every organization is kept in, under a key that starts with the organization
 * @param accessKey the key that identifies the platform to the store
 * @param secretKey the secret that goes with it
 * @param pathStyle whether the bucket is named in the path ({@code endpoint/bucket/key}) and not in the host name,
 *        which self-hosted stores need
 * @param socketTimeout how long a read from the store may wait for the next bytes (default 60 seconds)
 * @param callTimeout how long one call to the store may take in all (default 3 minutes)
 */
@ConfigurationProperties("platform.storage")
record StorageProperties(@DefaultValue("") String endpoint, @DefaultValue("us-east-1") String region,
        @DefaultValue("") String bucket, @DefaultValue("") String accessKey, @DefaultValue("") String secretKey,
        @DefaultValue("false") boolean pathStyle, @DefaultValue("60s") java.time.Duration socketTimeout,
        @DefaultValue("3m") java.time.Duration callTimeout) {

    /** Whether a store is configured: files are refused (a clean 503) when it is not. */
    boolean configured() {
        return !bucket.isBlank() && !accessKey.isBlank() && !secretKey.isBlank();
    }

    @Override
    public String toString() {
        return "StorageProperties[redacted]";
    }
}
