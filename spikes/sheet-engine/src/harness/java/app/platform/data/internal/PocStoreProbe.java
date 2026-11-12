package app.platform.data.internal;

import app.platform.sharedkernel.storage.ObjectStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * POC 2, WP2.5. Proves that the LOCAL S3-compatible store works with the platform's own object-store class: one small object is
 * written, read back, compared and removed. Status only. The keys come from the environment (never printed). This does NOT go through
 * the record-files endpoint of a fill-in header; that needs the application running and a fill-in, and was not run.
 */
public final class PocStoreProbe {

    private PocStoreProbe() {
    }

    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("S3_PORT", "8333");
        StorageProperties p = new StorageProperties("http://localhost:" + port, "us-east-1", "platform-files", System.getenv("S3_ACCESS_KEY"),
                System.getenv("S3_SECRET_KEY"), true, Duration.ofSeconds(10), Duration.ofSeconds(30));
        S3ObjectStore store = new S3ObjectStore(p);
        System.out.println("configured=" + store.configured() + " reachable before the bucket is made=" + store.reachable());
        try {
            store.ensureBucket(); // the application does this at start in the local profile
        } catch (RuntimeException e) {
            System.out.println("the bucket could not be made: " + e.getClass().getSimpleName());
        }
        System.out.println("reachable after=" + store.reachable());
        if (!store.reachable()) {
            System.exit(2);
        }
        String key = "poc2-probe/" + UUID.randomUUID();
        byte[] bytes = "poc2 probe: not a form".getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
        byte[] back;
        try (ObjectStore.Stored s = store.get(key)) {
            back = s.bytes().readAllBytes();
        }
        boolean same = java.util.Arrays.equals(bytes, back);
        store.delete(key);
        boolean gone = store.get(key) == null;
        System.out.println("put " + bytes.length + " bytes, read back " + back.length + ", identical=" + same + ", removed=" + gone);
        System.exit(same && gone ? 0 : 1);
    }
}
