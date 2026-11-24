package app.platform.integration.internal;

/**
 * The stable keys of why a webhook delivery ended or waits as it does, where the keys of
 * {@link FlowReason} and {@link TestReason} do not already say it (Sprint 32, ADR-0278). A key is stored in the
 * delivery
 * and the attempt, counted in a metric by name and shown to an administrator with the platform's own sentence; it never
 * holds a word of the receiver, an address or an identifier. The database checks that a stored key has the shape of
 * one.
 */
enum WebhookReason {

    /** The member the subscription acts as may not read the record now, so nothing was sent about it. */
    NOT_VISIBLE("not_visible", "The member this webhook acts as cannot read the record, so nothing was sent."),

    /** The member the subscription acts as is no longer an active member, so nothing is sent. */
    MEMBER_INACTIVE("member_inactive", "The member this webhook acts as can no longer act, so nothing was sent."),

    /** The subscription was switched off when the delivery was due. */
    SUBSCRIPTION_OFF("subscription_off", "The webhook is switched off."),

    /** The organization's plan does not include webhooks now, so nothing is sent until it does. */
    PLAN_OFF("plan_off", "The organization's plan does not include webhooks."),

    /** The subscription was removed. */
    SUBSCRIPTION_REMOVED("subscription_removed", "The webhook was removed."),

    /** The object is not one of those the subscription is limited to (it was changed after the delivery was made). */
    OBJECT_NOT_LISTED("object_not_listed", "The webhook is not limited to this object any more."),

    /** The record was removed for good before the delivery could say anything about it. */
    RECORD_PURGED("record_purged", "The record no longer exists."),

    /** The platform's own failure; nothing of it is kept but this key. */
    UNEXPECTED("unexpected", "The delivery could not be completed.");

    private final String key;
    private final String words;

    WebhookReason(String key, String words) {
        this.key = key;
        this.words = words;
    }

    /** The key that is stored and counted. */
    String key() {
        return key;
    }

    /** The platform's own sentence. */
    String words() {
        return words;
    }
}
