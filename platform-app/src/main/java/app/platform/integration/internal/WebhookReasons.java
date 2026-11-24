package app.platform.integration.internal;

/**
 * The platform's own sentence for a stable reason key of a webhook delivery or attempt (Sprint 32, ADR-0278). The keys
 * come from three closed lists that already have words: the reasons of a webhook, those of a flow and those of a
 * call. A key that is in none of them (it cannot happen: the database checks the shape and the code writes only
 * these) is shown as no words at all, never as text of the other side.
 */
final class WebhookReasons {

    private WebhookReasons() {
    }

    /** The sentence for the key, or null when there is no key or no sentence. */
    static String words(String key) {
        if (key == null) {
            return null;
        }
        for (WebhookReason reason : WebhookReason.values()) {
            if (reason.key().equals(key)) {
                return reason.words();
            }
        }
        for (FlowReason reason : FlowReason.values()) {
            if (reason.key().equals(key)) {
                return reason.words();
            }
        }
        TestReason call = TestReason.ofKey(key);
        return call == null || call == TestReason.OK ? null : call.words();
    }
}
