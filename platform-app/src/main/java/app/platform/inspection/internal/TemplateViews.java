package app.platform.inspection.internal;

import app.platformapi.QueryFilterNode;
import app.platformapi.QuerySortKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the endpoints of the cluster send and take (FA4, ADR-0188). They live here and not in the API contract module
 * because they carry the cluster's words; the OpenAPI document and the generated client carry them all the same.
 *
 * <p>Everything in them is for a screen to draw, never to decide: the flags say what to offer, and every request that
 * follows is decided again by the server. No view carries the text of a version (the grid), a group's name or a person.
 */
public final class TemplateViews {

    private TemplateViews() {
    }

    /**
     * What the versions of one template add up to. A number is 0 when no version has that status.
     *
     * @param state the state of the template as one word: {@code RETIRED}, {@code ACTIVE} (a version is in use),
     *        {@code APPROVED} (approved and not published), {@code IN_REVIEW}, {@code DRAFT} or {@code NONE}
     * @param activeVersion the version in use
     * @param approvedVersion the latest approved version that is not in use
     * @param inReviewVersion the version being reviewed
     * @param draftVersion the draft
     * @param retiredVersion the latest retired version
     * @param highestVersion the highest number
     */
    public record TemplateLifecycle(@NotNull String state, @NotNull Integer activeVersion,
            @NotNull Integer approvedVersion, @NotNull Integer inReviewVersion, @NotNull Integer draftVersion,
            @NotNull Integer retiredVersion, @NotNull Integer highestVersion) {
    }

    /**
     * One template in the master list.
     *
     * @param id the header record
     * @param name its name
     * @param number its number, or null before the first approval
     * @param category the category code
     * @param partA the Part A text, or null
     * @param level the level code (FA6A), or null
     * @param documentType the document code that goes with the level (FA6A), or null
     * @param retired whether it is retired
     * @param lifecycle what its versions add up to
     * @param openChangeRequests how many change requests of it still wait for someone (open or accepted) among those
     *        the caller may see; 0 for a caller who may see none (FA6E)
     * @param revision the revision in use, written with two digits (00 is the first), or null when none was ever
     *        published (FA8)
     */
    public record TemplateRow(@NotNull UUID id, String name, String number, String category, String partA,
            String level, String documentType, @NotNull Boolean retired, @NotNull TemplateLifecycle lifecycle,
            @NotNull Integer openChangeRequests, String revision) {
    }

    /**
     * The question a list view puts to the master list (UI3A). The filter and the order are the closed query model of
     * the record store; nothing in it is a rule of who may see what, and a typed text in it is never logged.
     *
     * @param search a text matched against the name, the number and (a whole code) the identification and Part A
     * @param filter conditions on the fields of the template, or null
     * @param sort up to two keys; none gives the name order
     * @param state a lifecycle state to keep, or null
     * @param size the page size, at most 200; null is 50
     * @param cursor the cursor of the previous page of the same question, or null
     */
    public record TemplateQuery(@Size(max = 80) String search, @Valid QueryFilterNode filter,
            @Size(max = 2) List<@NotNull @Valid QuerySortKey> sort, @Size(max = 20) String state, Integer size,
            @Size(max = 2000) String cursor) {

        @Override
        public String toString() {
            return "TemplateQuery[redacted]";
        }
    }

    /**
     * One page of the master list.
     *
     * @param rows the templates the caller may see, in the order asked
     * @param nextCursor the cursor of the next page of the same query, or null on the last page
     * @param canCreate whether the screen may offer to make a template; the request is decided again
     * @param filtered whether a lifecycle filter was applied after the page was read, so that a page may hold
     *        fewer rows than asked and the next page may still have some
     */
    public record TemplatePage(@NotNull List<TemplateRow> rows, String nextCursor, @NotNull Boolean canCreate,
            @NotNull Boolean filtered) {
    }

    /**
     * One group a template is published to.
     *
     * @param location the location code
     * @param department the department code
     * @param locationLabel what people read for the location
     * @param departmentLabel what people read for the department
     * @param versionNo the version the group sees
     * @param publishedAt when the group was first given the template
     * @param groupAvailable false when the group has been removed since: nobody reaches the template through it
     */
    public record TemplateAudience(@NotNull String location, @NotNull String department, @NotNull String locationLabel,
            @NotNull String departmentLabel, @NotNull Integer versionNo, @NotNull Instant publishedAt,
            @NotNull Boolean groupAvailable) {
    }

    /**
     * The lifecycle of one template, with what a screen may offer.
     *
     * @param id the header record
     * @param retired whether it is retired
     * @param lifecycle what its versions add up to
     * @param audience the groups it is published to now
     * @param canPublish whether to offer Publish: the ability, a version to publish, the template not retired, and the
     *        caller one of the people who gave the last real approval of that version (or an administrator), FA6E
     * @param canUnpublish whether to offer Unpublish (the ability and a version in use)
     * @param canRetire whether to offer Retire (the ability, not retired)
     * @param numberPreview what the number will start with, or be, while the template has none; null once it has one or
     *        when it cannot be numbered yet. Never the counter: showing it would mean taking it (ADR-0176 section 1)
     * @param numberPreviewComplete whether the preview is the whole number (a number made of typed parts only)
     * @param openChangeRequests how many change requests of the template still wait for someone (open or accepted)
     *        among those the caller may see (FA6E)
     * @param versionReviews which approval request reviewed which version, lowest version first, so a screen can name
     *        each approval of the history by its version (FA6E)
     * @param suggestedAudience the places a step of the review suggested for the version Publish would make current,
     *        ready to fill the Publish popup; empty for a caller who may not publish, when there is none, and when the
     *        step was skipped (FA6G). A suggestion shares nothing
     * @param suggestionDropped how many suggested places are no longer offered by the organization's lists and were
     *        left out of {@code suggestedAudience}
     * @param number the template's number, or null before the first approval (FA7: the sheet's footer row reads it)
     * @param viewOnly the template is only a reference: nobody can start a fill-in from it (FA7, ADR-0201)
     * @param allowDownload the sheet may be given as a file to everybody who can read it
     * @param canDownload whether to offer Download: the option is on, or the caller holds the right to publish, and a
     *        revision exists that the caller can read
     * @param canSetOptions whether to offer the "Sheet options" control (the right to publish)
     * @param revision the revision in use, written with two digits (00 is the first), or null when none was ever
     *        published; the platform saves it on the template when a revision is published (FA8)
     * @param lastRevisedAt when the revision in use was published, or null
     * @param canSubmit whether the template can be sent for approval now (FA10): the template's own rule, which the
     *        approval hook asks again; a screen greys Submit out when it is false and decides nothing else
     * @param submitNote a short fixed sentence for the hover of the greyed button, or null when it can be sent
     * @param footerDate whether the footer of this application's sheets carries the day the Revision in use was
     *        published (MC1, ADR-0257); the screen writes the day in the reader's own time zone
     * @param limitedToSuggested whether the caller may publish only to the locations the approval chose (FA15,
     *        ADR-0265): they lack the ability to publish to any location and a suggestion exists; the server refuses
     *        the rest, the screen only offers what this says
     * @param noPlaceLeft whether a suggestion exists and none of its places is offered any more, so a limited caller
     *        cannot publish
     * @param unpublishedPlaces the suggested locations no department of is published to, for a caller who may publish,
     *        so that a location is not forgotten
     */
    public record TemplateLifecycleView(@NotNull UUID id, @NotNull Boolean retired,
            @NotNull TemplateLifecycle lifecycle, @NotNull List<TemplateAudience> audience,
            @NotNull Boolean canPublish, @NotNull Boolean canUnpublish, @NotNull Boolean canRetire,
            String numberPreview, @NotNull Boolean numberPreviewComplete, @NotNull Integer openChangeRequests,
            @NotNull List<VersionReview> versionReviews, @NotNull List<SuggestedAudience> suggestedAudience,
            @NotNull Integer suggestionDropped, String number, @NotNull Boolean viewOnly,
            @NotNull Boolean allowDownload, @NotNull Boolean canDownload, @NotNull Boolean canSetOptions,
            String revision, Instant lastRevisedAt, @NotNull Boolean canSubmit, String submitNote,
            @NotNull Boolean footerDate, @NotNull Boolean limitedToSuggested, @NotNull Boolean noPlaceLeft,
            @NotNull List<UnpublishedPlace> unpublishedPlaces) {
    }

    /**
     * A location the approval chose that nothing is published to yet (FA15, ADR-0265).
     *
     * @param location the location code
     * @param locationLabel what people read for the location
     */
    public record UnpublishedPlace(@NotNull String location, @NotNull String locationLabel) {

        /** Prints nothing: the codes are the organization's text. */
        @Override
        public String toString() {
            return "UnpublishedPlace[redacted]";
        }
    }

    /**
     * One place a step of the review suggested.
     *
     * @param location the location code
     * @param locationLabel what people read for the location
     * @param department the department code, or null for the location alone
     * @param departmentLabel what people read for the department, or null
     */
    public record SuggestedAudience(@NotNull String location, @NotNull String locationLabel, String department,
            String departmentLabel) {
    }

    /**
     * The approval request that reviewed one version of a template.
     *
     * @param versionNo the version
     * @param requestId the approval request, as the approval history names it
     */
    public record VersionReview(@NotNull Integer versionNo, @NotNull UUID requestId) {
    }

    /**
     * One department a template can be published to.
     *
     * @param value the department code
     * @param label what people read
     * @param configured whether an administrator has set up a group for it (the publish says no to one that has none)
     */
    public record AudienceDepartment(@NotNull String value, @NotNull String label, @NotNull Boolean configured) {
    }

    /**
     * One location with the departments that go with it.
     *
     * @param value the location code
     * @param label what people read
     * @param departments the departments of this location, in the order the organization keeps them
     */
    public record AudienceLocation(@NotNull String value, @NotNull String label,
            @NotNull List<AudienceDepartment> departments) {
    }

    /**
     * What the publish dialog offers: the locations and, for each, its departments (the dependent lists).
     *
     * @param locations the locations
     * @param placesOnly whether this application publishes to places alone (MC1, ADR-0257): every location then
     *        carries the one department that receives the template, and the dialog asks for no department
     * @param placeDepartment the code of that department, or null when departments are chosen
     */
    public record AudienceOptionsView(@NotNull List<AudienceLocation> locations, @NotNull Boolean placesOnly,
            String placeDepartment) {

        /** The lists as the organization keeps them: departments are chosen. */
        public AudienceOptionsView(List<AudienceLocation> locations) {
            this(locations, false, null);
        }
    }

    /**
     * A location and department to publish to.
     *
     * @param location the location code
     * @param department the department code
     */
    public record AudiencePick(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,10}") String location,
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,10}") String department) {

        /** Prints nothing: the codes are the organization's text and requests are logged at debug level. */
        @Override
        public String toString() {
            return "AudiencePick[redacted]";
        }
    }

    /**
     * A request to publish.
     *
     * @param versionNo the approved version to make current, or null for the latest approved one (when that version is
     *        already in use, only the audience changes)
     * @param audiences the locations and departments to publish to, at least one and at most 20; a group that is
     *        already published to and is not listed here is taken back
     * @param viewOnly the "view only" option (FA7); left out keeps what the template has
     * @param allowDownload the "allow download" option (FA7); left out keeps what the template has
     */
    public record PublishTemplateRequest(Integer versionNo,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid AudiencePick> audiences, Boolean viewOnly,
            Boolean allowDownload) {
    }

    /**
     * The two options of a template's sheet, set again without publishing (FA7, ADR-0201).
     *
     * @param viewOnly nobody can start a fill-in from the template
     * @param allowDownload the sheet may be given as a file to everybody who can read it
     */
    public record SheetOptionsRequest(@NotNull Boolean viewOnly, @NotNull Boolean allowDownload) {
    }

    /**
     * What a person typing the identity of a template asks while they type (FA11B, ADR-0206). The parts are what is
     * typed so far, possibly nothing; the answer is advice and reserves nothing.
     *
     * @param category the identification chosen, or null
     * @param partA the Part A typed so far, or null
     * @param partB the Part B typed so far, or null
     */
    public record NumberingInfoRequest(@Size(max = 40) String category, @Size(max = 40) String partA,
            @Size(max = 40) String partB) {

        @Override
        public String toString() {
            return "NumberingInfoRequest[redacted]";
        }
    }

    /**
     * What the format of the chosen identification asks for and where its counter stands.
     *
     * @param known whether the identification has a number format; false means nothing is asked and no number is shown
     * @param partA whether the format reads Part A
     * @param partB whether the format reads Part B
     * @param serial whether the number ends in a running serial
     * @param width the digits the serial is padded to (0 without a serial)
     * @param prefix the number written so far, up to the first part still missing or up to the serial
     * @param complete whether every part the format reads is given
     * @param started whether this combination already has numbers, so that the serial is automatic and disabled
     * @param canStart whether the caller may type the first serial (the people who set the identification)
     * @param nextSerial the serial the next template of this combination gets, or null (given only to a caller who
     *        may type the serial)
     * @param number the whole number as it would be written, or null (with a serial only for a caller who may type
     *        the serial)
     */
    public record NumberingInfoView(@NotNull Boolean known, @NotNull Boolean partA, @NotNull Boolean partB,
            @NotNull Boolean serial, @NotNull Integer width, @NotNull String prefix, @NotNull Boolean complete,
            @NotNull Boolean started, @NotNull Boolean canStart, Long nextSerial, String number) {
    }
}
