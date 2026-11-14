package app.platform.inspection.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the endpoints of publishing a document to its plants send and take (MC2A, ADR-0260). They live here and not in
 * the API contract module because they carry the cluster's words; the OpenAPI document and the generated client carry
 * them all the same. Everything in them is for a screen to draw, never to decide: the flags say what to offer, and
 * every request that follows is decided again by the server.
 */
public final class PlantViews {

    /** The most plants one document is published to, and the most reviewers one plant has. */
    static final int MOST_PLANTS = 20;
    static final int MOST_REVIEWERS = 50;

    private PlantViews() {
    }

    /**
     * A person or group chosen as a reviewer.
     *
     * @param kind {@code USER} or {@code GROUP}
     * @param id the user or the group
     * @param name what people read, or null for a person who is no longer an active member or a group that was removed
     */
    public record PlantReviewerView(@NotNull String kind, @NotNull UUID id, String name) {
    }

    /**
     * One plant a document is published to, with its reviewers. Only a person who may publish the document to its
     * plants is told the reviewers.
     *
     * @param plant the plant's code
     * @param label what people read
     * @param publishedAt when the plant was first given the document
     * @param groupExists whether the group of plant heads still exists (false: nobody there can read the document until
     *        the administrator sets the group up again)
     * @param reviewers who reviews the fill-ins of this plant
     */
    public record PlantPublicationView(@NotNull String plant, @NotNull String label, @NotNull Instant publishedAt,
            @NotNull Boolean groupExists, @NotNull List<PlantReviewerView> reviewers) {
    }

    /**
     * Whether the document can be published to plants and what it is published to now.
     *
     * @param available whether the document belongs to an application that publishes to plants
     * @param canPublish whether the caller may publish it to plants now: they made it (or hold the ability), a Revision
     *        is in use and it is not retired. The flag only hides a button; the act is decided again
     * @param reason why not, in the platform's words, or null
     * @param plants the plants, for a caller who may publish; empty for anybody else
     */
    public record PlantPublicationState(@NotNull Boolean available, @NotNull Boolean canPublish, String reason,
            @NotNull List<PlantPublicationView> plants) {
    }

    /**
     * A reviewer in a request.
     *
     * @param kind {@code USER} or {@code GROUP}
     * @param id the user or the group
     */
    public record PlantReviewerPick(@NotNull @Pattern(regexp = "USER|GROUP") String kind, @NotNull UUID id) {

        /** Prints nothing: a reviewer is the organization's choice and requests are logged at debug level. */
        @Override
        public String toString() {
            return "PlantReviewerPick[redacted]";
        }
    }

    /**
     * A plant to publish to, with its reviewers.
     *
     * @param plant the plant's code (a location of the organization's list)
     * @param reviewers who reviews the fill-ins of this plant, at least one
     */
    public record PlantPick(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,10}") String plant,
            @NotNull @Size(min = 1, max = MOST_REVIEWERS) List<@NotNull @Valid PlantReviewerPick> reviewers) {

        /** Prints nothing: the plant and its reviewers are the organization's text and requests are logged. */
        @Override
        public String toString() {
            return "PlantPick[redacted]";
        }
    }

    /**
     * The plants a document is published to, as a whole: a plant listed and not published to yet is added, one listed
     * with other reviewers has them changed, and one published to and not listed is taken back. An empty list takes the
     * document back from every plant.
     *
     * @param plants the plants, at most 20
     */
    public record PublishPlantsRequest(@NotNull @Size(max = MOST_PLANTS) List<@NotNull @Valid PlantPick> plants) {
    }

    /**
     * A person or group a reviewer can be chosen from.
     *
     * @param kind {@code USER} or {@code GROUP}
     * @param id the user or the group
     * @param label what people read
     */
    public record ReviewerChoice(@NotNull String kind, @NotNull UUID id, @NotNull String label) {
    }

    /**
     * What a person types to find a reviewer. Typed text: sent in the body, never in the address, never printed.
     *
     * @param text part of a name, at most 80 characters
     */
    public record ReviewerSearch(@Size(max = 80) String text) {

        /** Prints nothing: what a person typed is never logged. */
        @Override
        public String toString() {
            return "ReviewerSearch[redacted]";
        }
    }

    /**
     * People and groups whose name contains the text, for the reviewer picker (bounded; a person needs the text to
     * match, so the list cannot be used to list the directory).
     *
     * @param people active members
     * @param groups public groups
     */
    public record ReviewerChoices(@NotNull List<ReviewerChoice> people, @NotNull List<ReviewerChoice> groups) {
    }
}
