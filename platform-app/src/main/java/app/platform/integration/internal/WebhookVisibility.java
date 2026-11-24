package app.platform.integration.internal;

import app.platform.integration.internal.WebhookSubscriptionStore.Subscription;
import app.platform.security.Admission;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import app.platform.security.RecordSecurity;
import app.platform.sharedkernel.directory.ReferenceLabels;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether a webhook may tell its receiver about a record, judged at the moment of delivery for the member the
 * subscription acts as (Sprint 32, ADR-0277 item 3). An event is written when a change is made, with no viewer; if it
 * were sent as it stands, the receiver would learn that records exist and that they changed, which the member the
 * subscription stands for may not be allowed to know. So the question is asked again when the delivery is made, through
 * the one rule of the platform ({@link Decisions}, which applies the object permission, the licence and the
 * record-level
 * security of ADR-0115), never through a rule of this class: it adds none.
 *
 * <p>Four answers: send; the member cannot read the record now ({@link Verdict#NOT_VISIBLE}, a counted and visible skip
 * that names no record in any count, log or metric); the member is no longer an active member
 * ({@link Verdict#MEMBER_INACTIVE}, the subscription is stopped and nothing is sent); the object is not one the
 * subscription is limited to ({@link Verdict#OBJECT_NOT_LISTED}).
 *
 * <p>A removed record is out of the record store's reach for the owner and the shares that could once have shown it to
 * the member, so a removal is told only to a member who could read <em>every</em> record of the object (public read, or
 * view all); to any other member it is skipped. That errs on the side of telling less: the receiver of such a
 * subscription finds the record gone the next time it fetches it. Must run in a transaction in the organization's own
 * tenant context.
 */
@Component
class WebhookVisibility {

    /** The decision. */
    enum Verdict {
        SEND,
        NOT_VISIBLE,
        MEMBER_INACTIVE,
        OBJECT_NOT_LISTED
    }

    private static final String USER_OBJECT = "User";

    private final Decisions decisions;
    private final RecordSecurity recordSecurity;
    private final List<ReferenceLabels> labels;

    WebhookVisibility(Decisions decisions, RecordSecurity recordSecurity, List<ReferenceLabels> labels) {
        this.decisions = decisions;
        this.recordSecurity = recordSecurity;
        this.labels = List.copyOf(labels);
    }

    /** Judges one delivery. */
    Verdict judge(Subscription subscription, String eventType, String object, UUID recordId) {
        if (subscription.objects() != null && !subscription.objects().contains(object)) {
            return Verdict.OBJECT_NOT_LISTED;
        }
        if (!memberActive(subscription.userId())) {
            return Verdict.MEMBER_INACTIVE;
        }
        UUID member = subscription.membershipId();
        if (WebhookEvents.DELETED.equals(eventType)) {
            boolean mayRead = decisions.can(member, object, ObjectAction.READ).allowed();
            boolean everyRecord = mayRead && recordSecurity.accessTo(member, object).read() instanceof
                    Admission.Everything;
            return everyRecord ? Verdict.SEND : Verdict.NOT_VISIBLE;
        }
        return decisions.can(member, object, ObjectAction.READ, new RecordRef(recordId)).allowed()
                ? Verdict.SEND : Verdict.NOT_VISIBLE;
    }

    /** Whether the person has an active membership of the organization now: the same answer as a link to a user. */
    boolean memberActive(UUID userId) {
        for (ReferenceLabels directory : labels) {
            if (USER_OBJECT.equals(directory.object())) {
                return directory.labels(List.of(userId)).containsKey(userId);
            }
        }
        return false;
    }

    /** The display name of the member, or null when the person left or has no active membership. */
    String nameOf(UUID userId) {
        for (ReferenceLabels directory : labels) {
            if (USER_OBJECT.equals(directory.object())) {
                return directory.labels(List.of(userId)).get(userId);
            }
        }
        return null;
    }
}
