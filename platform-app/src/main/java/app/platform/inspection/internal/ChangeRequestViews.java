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
     * @param status {@code OPEN} (in progress), {@code DECLINED} or {@code DONE} (published); {@code ACCEPTED} only for
     *        a request left from the earlier way of working
     * @param versionNo the version the person read
     * @param requesterName who asked, or null for a person who has left
     * @param requesterDepartment the department of the person when they asked (kept as it was then), or null when they
     *        were in none
     * @param mine whether the caller asked
     * @param createdAt when it was made
     * @param answeredAt when it was last answered, or null while it is open
     * @param resultingVersion the revised copy of the sheet while it is being prepared, and the version this request
     *        published once it is done; null before a copy is made (a stored version number)
     * @param resultingVersionStatus the status of that version now, or null
     * @param placesTotal how many places its comments point at (a comment on the whole template points at none)
     * @param placesInActive how many of those places are still in the version in use
     * @param canComment whether the caller may add a comment now
     * @param canCreateCopy whether the caller may make the revised copy of the sheet now (FA8)
     * @param canChangeCopy whether the caller may change the revised copy now (FA8)
     * @param comments its comments, oldest first
     * @param templateId the template it is about
     * @param templateName the template's name, or null when the caller may not read it
     * @param templateNumber the template's number, or null when it has none or the caller may not read it
     * @param reviewId the record the approval engine reviews for this request (the address of its approval panel), or
     *        null for a request made before every request had an approval (FA6E, ADR-0199)
     * @param resultingRevision the revision number of that version once it has been the revision in use, or null
     * @param publishPrefill what the window of the last approval starts from, for a caller who may publish while the
     *        request is open; null otherwise
     */
    public record ChangeRequestView(@NotNull UUID id, @NotNull String title, @NotNull String status,
            @NotNull Integer versionNo, String requesterName, String requesterDepartment, @NotNull Boolean mine,
            @NotNull Instant createdAt,
            Instant answeredAt, Integer resultingVersion, String resultingVersionStatus,
            @NotNull Integer placesTotal, @NotNull Integer placesInActive, @NotNull Boolean canComment,
            @NotNull Boolean canCreateCopy,
            @NotNull Boolean canChangeCopy, @NotNull List<ChangeCommentView> comments, @NotNull UUID templateId,
            String templateName, String templateNumber, UUID reviewId, Integer resultingRevision,
            PublishPrefill publishPrefill) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "ChangeRequestView[redacted]";
        }
    }

    /**
     * What the window of the last approval starts from (FA8, ADR-0202): the audience the step that asks for a choice
     * suggested, and the two options the template has now.
     *
     * @param suggested the places Corporate QMS suggested, with the places the lists no longer offer left out
     * @param suggestionDropped how many suggested places were left out for that reason
     * @param viewOnly the "view only" option now
     * @param allowDownload the "allow download" option now
     */
    public record PublishPrefill(@NotNull List<TemplateViews.SuggestedAudience> suggested,
            @NotNull Integer suggestionDropped, @NotNull Boolean viewOnly, @NotNull Boolean allowDownload) {
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
     * @param openCount how many of the requests are in progress on the template (at most one)
     * @param inProgress whether a change is in progress on the template, so another request cannot be raised now
     */
    public record ChangeRequestsView(@NotNull List<ChangeRequestView> requests, @NotNull Boolean canReview,
            @NotNull Boolean canRequest, @NotNull Integer activeVersion, @NotNull Integer draftVersion,
            @NotNull Integer openCount, @NotNull Boolean inProgress) {
    }

    /**
     * One request in the list across templates (FA6E, ADR-0199): what the person needs to know what it is about and to
     * open it, without its comments.
     *
     * @param id its identifier
     * @param templateId the template it is about
     * @param templateName the template's name, or null when the caller may not read it
     * @param templateNumber the template's number, or null
     * @param title what the request is called
     * @param status {@code OPEN}, {@code ACCEPTED}, {@code DECLINED} or {@code DONE}
     * @param versionNo the version the person read
     * @param requesterName who asked, or null for a person who has left
     * @param requesterDepartment the department of the person when they asked, or null
     * @param mine whether the caller asked
     * @param createdAt when it was made
     * @param reviewId the record the approval engine reviews for this request, so a row can offer the decision of its
     *        approval (a tick and a cross) without opening the request; null for a request with no approval
     */
    public record ChangeRequestRow(@NotNull UUID id, @NotNull UUID templateId, String templateName,
            String templateNumber, @NotNull String title, @NotNull String status, @NotNull Integer versionNo,
            String requesterName, String requesterDepartment, @NotNull Boolean mine, @NotNull Instant createdAt,
            UUID reviewId) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "ChangeRequestRow[redacted]";
        }
    }

    /**
     * One page of the list across templates, paged by the key of the last row seen.
     *
     * @param rows the requests the caller may see
     * @param nextCursor the cursor of the next page of the same question, or null at the end; a page may be short and
     *        still have a cursor, because requests the caller may not see are skipped
     */
    public record ChangeRequestPage(@NotNull List<ChangeRequestRow> rows, String nextCursor) {
    }

    /**
     * The question the list across templates is asked.
     *
     * @param search a text matched against the title and the requester's name
     * @param sort {@code date} (the default), {@code version} or {@code status}
     * @param descending whether to order from the highest; null is newest first for date, ascending otherwise
     * @param templateId only the requests about this template, or null
     * @param status only this status, or null
     * @param size the page size, at most 200; null is 50
     * @param cursor the cursor of the previous page of the same question, or null
     */
    public record ChangeRequestQuery(@Size(max = 80) String search,
            @Pattern(regexp = "date|version|status") String sort, Boolean descending, UUID templateId,
            @Pattern(regexp = "OPEN|ACCEPTED|DECLINED|DONE") String status, Integer size,
            @Size(max = 600) String cursor) {

        /** Prints a marker only: a typed text is in it. */
        @Override
        public String toString() {
            return "ChangeRequestQuery[redacted]";
        }
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
     * A request to make (FA8, ADR-0202): the register form the person fills, the version they read and, if they like,
     * the places on the sheet they mean. The request is called by the first line of what is asked.
     *
     * @param versionNo the version that was read
     * @param register what the person types in the register form
     * @param comments the places on the sheet it points at, each with a note
     */
    public record CreateChangeRequest(@NotNull Integer versionNo, @NotNull @Valid RegisterViews.RegisterInput register,
            @Valid List<@Valid NewChangeComment> comments) {

        /** Prints a marker only: the text in it is the organization's and never reaches a log. */
        @Override
        public String toString() {
            return "CreateChangeRequest[redacted]";
        }
    }
}
