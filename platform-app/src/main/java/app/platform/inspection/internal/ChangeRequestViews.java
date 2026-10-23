package app.platform.inspection.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the endpoints of the change requests send and take (FA5, ADR-0191). They live here and not in the API contract
 * module because they carry the cluster's words; the OpenAPI document and the generated client carry them all the same.
 *
 * <p>Everything in them is for a screen to draw, never to decide: the flags say what to offer, and every request that
 * follows is decided again by the server. The titles, comments and reasons are text of the organization: they are shown
 * to the people who may see the request and are never written to a log, an audit record or an event.
 */
public final class ChangeRequestViews {

    private ChangeRequestViews() {
    }

    /**
     * One comment.
     *
     * @param id its identifier
     * @param kind the place: {@code TEMPLATE}, {@code ROW}, {@code COLUMN} or {@code CELL}
     * @param rowId the row it points at, or null
     * @param columnId the column it points at, or null
     * @param label the words that named the place when the comment was made
     * @param inActiveVersion whether the place is still there in the version in use ("no longer in this version" when
     *        not)
     * @param text what was said
     * @param createdAt when it was written
     * @param authorName who wrote it, or null for a person who has left
     * @param mine whether the caller wrote it
     */
    public record ChangeCommentView(@NotNull UUID id, @NotNull String kind, String rowId, String columnId,
            @NotNull String label, @NotNull Boolean inActiveVersion, @NotNull String text, @NotNull Instant createdAt,
            String authorName, @NotNull Boolean mine) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "ChangeCommentView[redacted]";
        }
    }

    /**
     * One request with its comments.
     *
     * @param id its identifier
     * @param title what it is called
     * @param status {@code OPEN}, {@code ACCEPTED}, {@code DECLINED} or {@code DONE}
     * @param versionNo the version the person read
     * @param requesterName who asked, or null for a person who has left
     * @param mine whether the caller asked
     * @param createdAt when it was made
     * @param answeredAt when it was last answered, or null while it is open
     * @param decisionNote the reason it was declined, or null
     * @param resultingVersion the version it was turned into, or null
     * @param resultingVersionStatus the status of that version now, or null
     * @param placesTotal how many places its comments point at (a comment on the whole template points at none)
     * @param placesInActive how many of those places are still in the version in use
     * @param canComment whether the caller may add a comment now
     * @param canAccept whether the caller may accept it now
     * @param canDecline whether the caller may decline it now
     * @param canTurn whether the caller may turn it into a new version now
     * @param canReopen whether the caller may reopen it (its version was not used)
     * @param comments its comments, oldest first
     */
    public record ChangeRequestView(@NotNull UUID id, @NotNull String title, @NotNull String status,
            @NotNull Integer versionNo, String requesterName, @NotNull Boolean mine, @NotNull Instant createdAt,
            Instant answeredAt, String decisionNote, Integer resultingVersion, String resultingVersionStatus,
            @NotNull Integer placesTotal, @NotNull Integer placesInActive, @NotNull Boolean canComment,
            @NotNull Boolean canAccept, @NotNull Boolean canDecline, @NotNull Boolean canTurn,
            @NotNull Boolean canReopen, @NotNull List<ChangeCommentView> comments) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "ChangeRequestView[redacted]";
        }
    }

    /**
     * The requests the caller may see on one template.
     *
     * @param requests the requests, newest first: all of them for a person who may change the template, their own for
     *        everybody else
     * @param canReview whether the caller may answer requests (they may change the template)
     * @param canRequest whether the screen may offer to ask for a change (a version is in use and the template is not
     *        retired)
     * @param activeVersion the version in use, or 0
     * @param draftVersion the draft, or 0
     * @param openCount how many of the requests are open
     */
    public record ChangeRequestsView(@NotNull List<ChangeRequestView> requests, @NotNull Boolean canReview,
            @NotNull Boolean canRequest, @NotNull Integer activeVersion, @NotNull Integer draftVersion,
            @NotNull Integer openCount) {
    }

    /**
     * A comment to make.
     *
     * @param rowId the row it points at, or null
     * @param columnId the column it points at, or null; both null means the template as a whole
     * @param text what to say
     */
    public record NewChangeComment(@Size(max = 40) String rowId, @Size(max = 40) String columnId,
            @NotNull @Size(max = 8000) String text) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "NewChangeComment[redacted]";
        }
    }

    /**
     * A request to make.
     *
     * @param title what to call it
     * @param versionNo the version that was read
     * @param comments the comments it starts with
     */
    public record CreateChangeRequest(@NotNull @Size(max = 1000) String title, @NotNull Integer versionNo,
            @Valid List<@Valid NewChangeComment> comments) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "CreateChangeRequest[redacted]";
        }
    }

    /**
     * An answer to a request.
     *
     * @param action {@code ACCEPT}, {@code DECLINE} (a reason is needed) or {@code REOPEN}
     * @param note the reason for declining
     */
    public record AnswerChangeRequest(@NotNull @Pattern(regexp = "ACCEPT|DECLINE|REOPEN") String action,
            @Size(max = 4000) String note) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "AnswerChangeRequest[redacted]";
        }
    }

    /**
     * Requests to turn into a version of the template.
     *
     * @param requestIds the requests, which must be open or accepted
     */
    public record TurnIntoVersionRequest(@NotNull @Size(min = 1, max = 200) List<@NotNull UUID> requestIds) {
    }

    /**
     * What turning requests into a version did.
     *
     * @param versionNo the draft they are now linked to
     * @param created true when the draft was made now, false when a draft already existed and the requests were linked
     *        to it as it is
     * @param requests how many requests were linked
     */
    public record TurnedView(@NotNull Integer versionNo, @NotNull Boolean created, @NotNull Integer requests) {
    }
}
