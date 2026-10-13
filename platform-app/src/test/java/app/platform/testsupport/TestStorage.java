package app.platform.testsupport;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * One real S3-compatible object store for the whole integration-test run, started once and shared (Sprint 17: the
 * bytes of files and imports). The image is the one the local environment runs, read from its compose file. The bucket
 * is made when the store is up. Tests read what the application stored through {@link #keys}; the container can be
 * paused to stand in for an outage of the store ({@link #pause}, {@link #resume}).
 *
 * <p>Tests share one bucket; the keys start with the organization, so each test looks at its own.
 */
public final class TestStorage {

    private static final Path COMPOSE_FILE = Path.of("../infra/local/docker-compose.yml");
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key-0123456789abcdef";
    private static final String BUCKET = "platform-test";
    private static final GenericContainer<?> STORE = new GenericContainer<>(storeImage())
            .withExposedPorts(8333)
            .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
            .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
            .withCommand("server", "-s3", "-dir=/data", "-master.volumeSizeLimitMB=128")
            .waitingFor(Wait.forHttp("/status").forPort(8333).withStartupTimeout(Duration.ofMinutes(2)));
    private static final S3Client CLIENT;

    static {
        STORE.start();
        CLIENT = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .endpointOverride(URI.create(endpoint())).build();
        makeBucket();
    }

    private TestStorage() {
    }

    private static String storeImage() {
        try {
            Matcher matcher = Pattern.compile("(?m)^\\s*image:\\s*(\\S*seaweed\\S*)\\s*$")
                    .matcher(Files.readString(COMPOSE_FILE));
            if (!matcher.find()) {
                throw new IllegalStateException("No object storage image in " + COMPOSE_FILE);
            }
            return matcher.group(1);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + COMPOSE_FILE, e);
        }
    }

    private static void makeBucket() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                CLIENT.createBucket(request -> request.bucket(BUCKET));
                return;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        throw new IllegalStateException("The test object store did not accept a bucket", last);
    }

    /** The address of the store. */
    public static String endpoint() {
        return "http://" + STORE.getHost() + ":" + STORE.getMappedPort(8333);
    }

    /** The bucket the application writes to. */
    public static String bucket() {
        return BUCKET;
    }

    /** The access key of the application. */
    public static String accessKey() {
        return ACCESS_KEY;
    }

    /** The secret key of the application. */
    public static String secretKey() {
        return SECRET_KEY;
    }

    /** The keys of the objects whose key starts with the prefix. */
    public static List<String> keys(String prefix) {
        return CLIENT.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build()).contents()
                .stream().map(S3Object::key).toList();
    }

    /** The bytes stored under the key. */
    public static byte[] bytes(String key) {
        return CLIENT.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray();
    }

    /** Stops the container's processes, so connections hang and time out: an outage of the store. */
    public static void pause() {
        STORE.getDockerClient().pauseContainerCmd(STORE.getContainerId()).exec();
    }

    /** Brings the store back after {@link #pause()}. */
    public static void resume() {
        STORE.getDockerClient().unpauseContainerCmd(STORE.getContainerId()).exec();
    }
}
