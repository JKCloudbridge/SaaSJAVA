package app.platform.integration.internal;

/**
 * Turns what was seen on the wire into a stable reason (Sprint 29A, ADR-0238). Pure: no network, no library, no
 * message. A failure is told from the <em>stage</em> it happened in and from the status code of the file protocol,
 * never from what the library or the server wrote, because that text can quote a path, a user name or a key.
 *
 * <p>Unknown is permanent: a status nobody listed is the general failure of the protocol, which may pass (a full disk,
 * a store that refuses) and is tried again a bounded number of times; a stage nobody listed is "could not set up a
 * session", which no waiting will mend.
 */
final class SshOutcome {

    // The status codes of the file protocol that matter here.
    static final int EOF = 1;
    static final int NO_SUCH_FILE = 2;
    static final int PERMISSION_DENIED = 3;
    static final int BAD_MESSAGE = 5;
    static final int NO_CONNECTION = 6;
    static final int CONNECTION_LOST = 7;
    static final int OP_UNSUPPORTED = 8;

    private SshOutcome() {
    }

    /**
     * Why a session could not be set up.
     *
     * @param keyRefused the key the server showed was not the pinned one
     * @param timedOut the time ran out or a read timed out
     * @param broken the other side closed the connection or it failed
     * @param keyAccepted the key was checked and was the pinned one
     */
    static TestReason connect(boolean keyRefused, boolean timedOut, boolean broken, boolean keyAccepted) {
        if (keyRefused) {
            return TestReason.HOST_KEY_MISMATCH;
        }
        if (timedOut) {
            return TestReason.TIMED_OUT;
        }
        if (broken) {
            return TestReason.RESPONSE_INVALID;
        }
        // Past the key check the only step left is signing in (and opening the file channel, which a server that has
        // accepted the sign-in does not refuse); before it, the two sides could not agree on how to talk.
        return keyAccepted ? TestReason.AUTHENTICATION_REJECTED : TestReason.SSH_FAILED;
    }

    /** Why a file operation failed, from the status of the protocol, the wire taking precedence. */
    static TestReason status(int status, boolean timedOut, boolean broken) {
        if (timedOut) {
            return TestReason.TIMED_OUT;
        }
        if (broken) {
            return TestReason.RESPONSE_INVALID;
        }
        return switch (status) {
            case NO_SUCH_FILE -> TestReason.PATH_NOT_FOUND;
            case PERMISSION_DENIED -> TestReason.PERMISSION_DENIED;
            case NO_CONNECTION, CONNECTION_LOST, EOF, BAD_MESSAGE -> TestReason.RESPONSE_INVALID;
            case OP_UNSUPPORTED -> TestReason.SSH_FAILED;
            default -> TestReason.FILE_FAILED;
        };
    }

    /** Why a read or write on an open channel failed with no status: the wire, else a refused file operation. */
    static TestReason wire(boolean timedOut, boolean broken) {
        if (timedOut) {
            return TestReason.TIMED_OUT;
        }
        return broken ? TestReason.RESPONSE_INVALID : TestReason.FILE_FAILED;
    }
}
