package app.platform.identity.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures what one password hash costs on the machine it runs on, for the parameters that ADR-0020 records. Not a
 * test: run it by hand when the hardware of a deployment changes ({@code java -cp ... Argon2Calibration}). It prints,
 * for each setting, the median time of one hash and of one verification.
 */
public final class Argon2Calibration {

    private static final int WARMUP = 5;
    private static final int SAMPLES = 25;

    private Argon2Calibration() {
    }

    /** Prints the table. */
    public static void main(String[] args) {
        int[][] settings = {
            {19_456, 2, 1}, {19_456, 3, 1}, {32_768, 2, 1}, {32_768, 3, 1}, {47_104, 1, 1}, {47_104, 2, 1},
            {65_536, 1, 1}, {65_536, 2, 1}, {65_536, 3, 1}, {19_456, 2, 2}};
        char[] password = "a typical passphrase of average length".toCharArray();
        System.out.printf("Java %s, %d processors, %d MiB max heap%n", System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("memory KiB | passes | lanes | hash ms (median) | verify ms (median)");
        for (int[] setting : settings) {
            PasswordHasher hasher = new PasswordHasher(new IdentityProperties.Password(
                    12, 128, setting[0], setting[1], setting[2], 4, Duration.ofSeconds(30)));
            String stored = hasher.hash(password);
            for (int i = 0; i < WARMUP; i++) {
                hasher.hash(password);
                hasher.matches(password, stored);
            }
            List<Long> hashing = new ArrayList<>();
            List<Long> verifying = new ArrayList<>();
            for (int i = 0; i < SAMPLES; i++) {
                long start = System.nanoTime();
                hasher.hash(password);
                hashing.add(System.nanoTime() - start);
                start = System.nanoTime();
                hasher.matches(password, stored);
                verifying.add(System.nanoTime() - start);
            }
            Collections.sort(hashing);
            Collections.sort(verifying);
            System.out.printf("%10d | %6d | %5d | %16.1f | %18.1f%n", setting[0], setting[1], setting[2],
                    hashing.get(SAMPLES / 2) / 1e6, verifying.get(SAMPLES / 2) / 1e6);
        }
    }
}
