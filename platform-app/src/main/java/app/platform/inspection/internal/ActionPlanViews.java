package app.platform.inspection.internal;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * What the endpoints of the Action plan send and take (MC2B, ADR-0261). They live here and not in the API contract
 * module because they carry the cluster's words. Everything in them is for a screen to draw, never to decide: the flags
 * say what to offer, and every request that follows is decided again by the server. A score, a limit, a plant and a
 * month are never in them.
 */
public final class ActionPlanViews {

    private ActionPlanViews() {
    }

    /**
     * A document that can be named as the Action plan form.
     *
     * @param id the document
     * @param name what people read
     * @param number its document number, or null while it has none
     */
    public record ActionPlanChoice(@NotNull UUID id, String name, String number) {
    }

    /**
     * The Action plan form of a document and who may change it.
     *
     * @param available whether the document belongs to an application that publishes to plants
     * @param canChange whether the caller may name the form (they made the document or hold the ability to publish to
     *        plants); the flag only hides a control, the act is decided again
     * @param actionTemplateId the document named, or null
     * @param actionName its name when the caller can read it, otherwise null
     * @param choices the documents of the same application the caller can read, for a caller who may change it
     */
    public record ActionPlanFormView(@NotNull Boolean available, @NotNull Boolean canChange, UUID actionTemplateId,
            String actionName, @NotNull List<ActionPlanChoice> choices) {
    }

    /**
     * The document named as the Action plan form, or none.
     *
     * @param actionTemplateId the document, or null to take the form back
     */
    public record SetActionPlanFormRequest(UUID actionTemplateId) {
    }

    /**
     * Whether a fill-in needs an Action plan and whether the caller may start it.
     *
     * @param needed whether the fill-in scored below the limit, so an Action plan answers it
     * @param canStart whether to offer Start: the caller is a plant head of its plant, may fill in, and the form is
     *        ready; the flag only hides a button
     * @param reason why not, in the platform's words, or null
     * @param actionPlanId the Action plan that answers it when the caller can read it, otherwise null
     * @param started whether an Action plan was started, whether or not the caller can read it
     */
    public record ActionPlanStateView(@NotNull Boolean needed, @NotNull Boolean canStart, String reason,
            UUID actionPlanId, @NotNull Boolean started) {
    }
}
