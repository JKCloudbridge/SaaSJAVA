package app.platform.data.internal;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The object store, reached with the S3 client of the SDK (Sprint 17, ADR-0120). The client is made on first use, so an
 * application that never touches a file (and every test that does not) needs no store and opens no connection. The
 * small HTTP client of the SDK is used on purpose. The checksum the SDK adds on its own to every request is switched
 * off (it is added only where the protocol requires it): several S3-compatible stores do not know it, and the
 * application checks the bytes with its own SHA-256.
 *
 * <p>Timeouts are short for a connection and long enough for a file of the largest size on a slow link. Every failure
 * of the SDK becomes an {@link ObjectStoreException}; its message is dropped (ADR-0012).
 */
@Component
class S3ObjectStore implements ObjectStore {

    private static final int RANGE_NOT_SATISFIABLE = 416;

    private final StorageProperties properties;
    private volatile S3Client client;

    S3ObjectStore(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public boolean reachable() {
        if (!configured()) {
            return false;
        }
        try {
            client().headBucket(request -> request.bucket(properties.bucket()));
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    @Override
    public void ensureBucket() {
        try {
            client().createBucket(request -> request.bucket(properties.bucket()));
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            // Already there: nothing to do.
        } catch (SdkException e) {
            throw new ObjectStoreException();
        }
    }

    @Override
    public void put(String key, InputStream bytes, long length, String contentType) {
        try {
            client().putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                    .contentType(contentType).contentLength(length).build(),
                    RequestBody.fromInputStream(bytes, length));
        } catch (SdkException e) {
            throw new ObjectStoreException();
        }
    }

    @Override
    public Stored get(String key, long fromOffset) {
        try {
            GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(properties.bucket()).key(key);
            if (fromOffset > 0) {
                request.range("bytes=" + fromOffset + "-");
            }
            ResponseInputStream<GetObjectResponse> response = client().getObject(request.build());
            return new Stored(new Aborting(response), response.response().contentLength());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            // A range that starts at the end of the object is "not satisfiable": there is nothing left to read.
            if (e.statusCode() == RANGE_NOT_SATISFIABLE) {
                return new Stored(InputStream.nullInputStream(), 0);
            }
            throw new ObjectStoreException();
        } catch (SdkException e) {
            throw new ObjectStoreException();
        }
    }

    @Override
    public void delete(String key) {
        try {
            client().deleteObject(request -> request.bucket(properties.bucket()).key(key));
        } catch (SdkException e) {
            throw new ObjectStoreException();
        }
    }

    /**
     * The bytes of an object. Closing it aborts the request instead of reading what is left (a reader that stopped at
     * the end of its batch must not pull the rest of a large file through the connection).
     */
    private static final class Aborting extends java.io.FilterInputStream {

        private final ResponseInputStream<GetObjectResponse> response;

        Aborting(ResponseInputStream<GetObjectResponse> response) {
            super(response);
            this.response = response;
        }

        @Override
        public void close() {
            response.abort();
        }
    }

    private S3Client client() {
        S3Client found = client;
        if (found == null) {
            synchronized (this) {
                found = client;
                if (found == null) {
                    found = build();
                    client = found;
                }
            }
        }
        return found;
    }

    private S3Client build() {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(properties.socketTimeout()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .forcePathStyle(properties.pathStyle())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                // No retries: an upload streams the body once (it cannot be replayed, so a retried attempt would
                // fail with its own confusing error instead of the clean one this class gives); a failure of any
                // call is answered the same way to the caller whether it is the first attempt or a retry.
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(properties.callTimeout())
                        .retryStrategy(retry -> retry.maxAttempts(1)));
        if (!properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }
}
