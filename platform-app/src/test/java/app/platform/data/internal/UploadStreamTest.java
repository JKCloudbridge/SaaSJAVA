package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The bytes of an upload as they pass to the object store (ADR-0120): counted, hashed, cut at the announced size. */
class UploadStreamTest {

    private static MessageDigest digest() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }

    @Test
    void itCountsAndHashesWhatPassesAndSaysItEndedAsAnnounced() throws Exception {
        byte[] bytes = "the content of a file".getBytes(StandardCharsets.UTF_8);
        UploadStream stream = new UploadStream(new ByteArrayInputStream(bytes), bytes.length, digest());

        byte[] read = stream.readAllBytes();

        assertThat(read).isEqualTo(bytes);
        assertThat(stream.count()).isEqualTo(bytes.length);
        assertThat(stream.endedEarly()).isFalse();
        assertThat(stream.sha256()).isEqualTo(HexFormat.of().formatHex(digest().digest(bytes)));
    }

    @Test
    void itNeverPassesMoreThanTheAnnouncedLengthWhateverTheSourceHolds() throws Exception {
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        UploadStream stream = new UploadStream(new ByteArrayInputStream(bytes), 4, digest());

        byte[] read = stream.readAllBytes();

        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("0123");
        assertThat(stream.count()).isEqualTo(4);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void aSourceThatEndsEarlyIsRememberedAndNotTreatedAsComplete() throws Exception {
        UploadStream stream = new UploadStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), 10, digest());

        stream.readAllBytes();

        assertThat(stream.count()).isEqualTo(3);
        assertThat(stream.endedEarly()).isTrue();
    }

    @Test
    void readingOneByteAtATimeGivesTheSameCountAndHash() throws Exception {
        byte[] bytes = new byte[300];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        UploadStream stream = new UploadStream(new ByteArrayInputStream(bytes), bytes.length, digest());
        int read = 0;
        while (stream.read() >= 0) {
            read++;
        }

        assertThat(read).isEqualTo(300);
        assertThat(stream.count()).isEqualTo(300);
        assertThat(stream.sha256()).isEqualTo(HexFormat.of().formatHex(digest().digest(bytes)));
    }

    @Test
    void closingItClosesTheSource() throws IOException, NoSuchAlgorithmException {
        boolean[] closed = {false};
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[1]) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };

        new UploadStream(source, 1, digest()).close();

        assertThat(closed[0]).isTrue();
    }
}
