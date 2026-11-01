package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.RetryPolicy.Health;
import app.platform.integration.internal.RetryPolicy.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * Every way the secure shell transport can fail maps to a stable reason, and every reason is retried or not as the
 * decision says (Sprint 29A, ADR-0237, ADR-0238). The mapping is pure: it is told what was seen on the wire and which
 * status the file protocol gave, never what the library or the server wrote. A key that is not the pinned one and a
 * credential that is refused are never retried; a full disk or a refusing store is; unknown is permanent.
 */
class TransportOutcomeTest {

    @Test
    void aSessionThatCouldNotBeSetUpIsToldFromTheStageItFailedIn() {
        // keyRefused, timedOut, broken, keyAccepted
        assertThat(SshOutcome.connect(true, false, false, false)).isEqualTo(TestReason.HOST_KEY_MISMATCH);
        assertThat(SshOutcome.connect(true, true, true, true)).as("a wrong key wins over everything")
                .isEqualTo(TestReason.HOST_KEY_MISMATCH);
        assertThat(SshOutcome.connect(false, true, false, false)).isEqualTo(TestReason.TIMED_OUT);
        assertThat(SshOutcome.connect(false, true, true, true)).as("the clock wins over a broken wire")
                .isEqualTo(TestReason.TIMED_OUT);
        assertThat(SshOutcome.connect(false, false, true, true)).isEqualTo(TestReason.RESPONSE_INVALID);
        assertThat(SshOutcome.connect(false, false, false, true)).as("past the key check: signing in")
                .isEqualTo(TestReason.AUTHENTICATION_REJECTED);
        assertThat(SshOutcome.connect(false, false, false, false)).as("before the key check: no common ground")
                .isEqualTo(TestReason.SSH_FAILED);
    }

    @Test
    void aFileOperationIsToldFromTheStatusOfTheProtocolAndTheWireComesFirst() {
        assertThat(SshOutcome.status(SshOutcome.NO_SUCH_FILE, false, false)).isEqualTo(TestReason.PATH_NOT_FOUND);
        assertThat(SshOutcome.status(SshOutcome.PERMISSION_DENIED, false, false))
                .isEqualTo(TestReason.PERMISSION_DENIED);
        for (int lost : new int[] {SshOutcome.EOF, SshOutcome.BAD_MESSAGE, SshOutcome.NO_CONNECTION,
            SshOutcome.CONNECTION_LOST}) {
            assertThat(SshOutcome.status(lost, false, false)).as("status " + lost)
                    .isEqualTo(TestReason.RESPONSE_INVALID);
        }
        assertThat(SshOutcome.status(SshOutcome.OP_UNSUPPORTED, false, false)).isEqualTo(TestReason.SSH_FAILED);
        assertThat(SshOutcome.status(4, false, false)).as("the general failure: a full disk, a store that refuses")
                .isEqualTo(TestReason.FILE_FAILED);
        assertThat(SshOutcome.status(SshOutcome.PERMISSION_DENIED, true, false)).isEqualTo(TestReason.TIMED_OUT);
        assertThat(SshOutcome.status(SshOutcome.NO_SUCH_FILE, false, true)).isEqualTo(TestReason.RESPONSE_INVALID);
        assertThat(SshOutcome.wire(false, false)).isEqualTo(TestReason.FILE_FAILED);
        assertThat(SshOutcome.wire(false, true)).isEqualTo(TestReason.RESPONSE_INVALID);
        assertThat(SshOutcome.wire(true, true)).isEqualTo(TestReason.TIMED_OUT);
    }

    @Property
    void anyStatusNobodyListedIsTheGeneralFailureAndNeverSomethingElse(@ForAll @IntRange(min = 9, max = 100_000)
            int status) {
        assertThat(SshOutcome.status(status, false, false)).isEqualTo(TestReason.FILE_FAILED);
    }

    @Test
    void aKeyThatIsNotPinnedAndACredentialThatIsRefusedAreNeverTriedAgain() {
        for (TestReason never : List.of(TestReason.HOST_KEY_MISMATCH, TestReason.AUTHENTICATION_REJECTED)) {
            assertThat(RetryPolicy.failure(never).kind()).as(never.key()).isEqualTo(Kind.PERMANENT);
            assertThat(ConnectionFailures.stops(never)).as(never.key()).isTrue();
        }
        assertThat(RetryPolicy.failure(TestReason.HOST_KEY_MISMATCH).health()).as("a stranger is not an outage")
                .isEqualTo(Health.NEUTRAL);
    }

    @Test
    void aFullDiskAVanishedConnectionAndATimeoutAreTriedAgainAndStopNothing() {
        for (TestReason again : List.of(TestReason.FILE_FAILED, TestReason.RESPONSE_INVALID, TestReason.TIMED_OUT,
                TestReason.CONNECT_FAILED, TestReason.NAME_NOT_FOUND)) {
            assertThat(RetryPolicy.failure(again).kind()).as(again.key()).isEqualTo(Kind.RETRY);
            assertThat(RetryPolicy.failure(again).health()).as(again.key()).isEqualTo(Health.UNHEALTHY);
            assertThat(ConnectionFailures.stops(again)).as(again.key()).isFalse();
        }
    }

    @Test
    void aMissingDirectoryAndAMissingRightAreAnswersOfALivingServerThatNoWaitingChanges() {
        for (TestReason answer : List.of(TestReason.PATH_NOT_FOUND, TestReason.PERMISSION_DENIED)) {
            assertThat(RetryPolicy.failure(answer).kind()).as(answer.key()).isEqualTo(Kind.PERMANENT);
            assertThat(RetryPolicy.failure(answer).health()).as(answer.key()).isEqualTo(Health.ALIVE);
            assertThat(ConnectionFailures.stops(answer)).as(answer.key()).isFalse();
        }
        assertThat(RetryPolicy.failure(TestReason.SSH_FAILED).kind()).isEqualTo(Kind.PERMANENT);
    }

    @Test
    void everyReasonIsAStableKeyThatTheDatabaseAccepts() {
        for (TestReason reason : TestReason.values()) {
            assertThat(reason.key()).as(reason.name()).matches("[a-z_]{1,40}");
            assertThat(TestReason.ofKey(reason.key())).isEqualTo(reason);
            assertThat(FlowReason.wordsFor(reason.key())).as(reason.name()).isNotBlank();
        }
        for (FlowReason reason : FlowReason.values()) {
            assertThat(reason.key()).as(reason.name()).matches("[a-z_]{1,40}");
        }
        for (String key : List.of("file_empty", "file_not_text", "file_too_many_rows", "file_header_invalid",
                "file_row_invalid", "file_rows_refused", "file_duplicate", "file_vanished", "file_too_large")) {
            assertThat(FlowReason.wordsFor(key)).as(key).isNotBlank()
                    .isNotEqualTo(FlowReason.UNEXPECTED.words());
        }
    }

    @Test
    void theFingerprintOfAKeyIsTheOneTheUsualToolsPrint() {
        // The SHA-256 of nothing, in the unpadded base64 the tools print after "SHA256:".
        assertThat(SftpTransport.fingerprint(new byte[0]))
                .isEqualTo("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU");
        assertThat(SftpTransport.fingerprint("a key".getBytes(StandardCharsets.UTF_8)))
                .matches("SHA256:[A-Za-z0-9+/]{43}");
    }
}
