package app.platform.integration.internal;

/**
 * Why a connection test ended as it did (ADR-0210). The key is stable and is the only thing stored, counted or shown
 * next to the sentence; the sentence is the platform's own. Nothing the external system said, no header, no body and no
 * message of a library ever reaches a reason.
 */
enum TestReason {

    OK("ok", "The service answered and accepted the connection.", true),
    ADDRESS_NOT_ALLOWED("address_not_allowed",
            "The address points to a network the platform does not connect to.", false),
    NAME_NOT_FOUND("name_not_found", "The name of the service could not be found.", false),
    CONNECT_FAILED("connect_failed", "The platform could not connect to the service.", false),
    TLS_FAILED("tls_failed", "A secure connection to the service could not be set up.", false),
    TIMED_OUT("timed_out", "The service did not answer in time.", false),
    RESPONSE_INVALID("response_invalid", "The service gave an answer the platform could not read.", false),
    RESPONSE_TOO_LARGE("response_too_large", "The service's answer was larger than the platform accepts.", false),
    REDIRECT_REFUSED("redirect_refused", "The service tried to send the platform elsewhere, which it does not do.",
            false),
    UNEXPECTED_STATUS("unexpected_status", "The service answered, but not in the way this connection expects.",
            false),
    AUTHENTICATION_REJECTED("authentication_rejected", "The service did not accept the credential.", false),
    CREDENTIAL_MISSING("credential_missing", "The credential this connection names was not found.", false),
    CREDENTIAL_UNUSABLE("credential_unusable", "The credential this connection names cannot be sent.", false),
    CREDENTIAL_STORE_UNAVAILABLE("credential_store_unavailable",
            "Credentials cannot be read at this time, so a connection that needs one cannot be tested.", false),
    CHANGED_DURING_TEST("changed_during_test", "The connection was changed while it was tested. Test it again.",
            false),
    CREDENTIAL_CHANGED("credential_changed",
            "The credential was replaced or removed after the last test. Test the connection again.", false),
    UNEXPECTED("unexpected", "The test could not be completed.", false);

    private final String key;
    private final String words;
    private final boolean passed;

    TestReason(String key, String words, boolean passed) {
        this.key = key;
        this.words = words;
        this.passed = passed;
    }

    String key() {
        return key;
    }

    String words() {
        return words;
    }

    boolean passed() {
        return passed;
    }

    /** The reason with this stored key, or {@link #UNEXPECTED} for one this release does not know. */
    static TestReason ofKey(String key) {
        for (TestReason reason : values()) {
            if (reason.key.equals(key)) {
                return reason;
            }
        }
        return UNEXPECTED;
    }
}
