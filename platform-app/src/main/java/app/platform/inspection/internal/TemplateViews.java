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
     * @param subCategory the sub-category code, or null
     * @param retired whether it is retired
     * @param lifecycle what its versions add up to
     */
    public record TemplateRow(@NotNull UUID id, String name, String number, String category, String subCategory,
            @NotNull Boolean retired, @NotNull TemplateLifecycle lifecycle) {
    }

    /**
     * The question a list view puts to the master list (UI3A). The filter and the order are the closed query model of
     * the record store; nothing in it is a rule of who may see what, and a typed text in it is never logged.
     *
     * @param search a text matched against the name, the number and (a whole code) the category and sub-category
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
     * @param canPublish whether to offer Publish (the ability, a version to publish, not retired)
     * @param canUnpublish whether to offer Unpublish (the ability and a version in use)
     * @param canRetire whether to offer Retire (the ability, not retired)
     * @param numberPreview what the number will start with, or be, while the template has none; null once it has one or
     *        when it cannot be numbered yet. Never the counter: showing it would mean taking it (ADR-0176 section 1)
     * @param numberPreviewComplete whether the preview is the whole number (a number made of typed parts only)
     */
    public record TemplateLifecycleView(@NotNull UUID id, @NotNull Boolean retired,
            @NotNull TemplateLifecycle lifecycle, @NotNull List<TemplateAudience> audience,
            @NotNull Boolean canPublish, @NotNull Boolean canUnpublish, @NotNull Boolean canRetire,
            String numberPreview, @NotNull Boolean numberPreviewComplete) {
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
     */
    public record AudienceOptionsView(@NotNull List<AudienceLocation> locations) {
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
    }

    /**
     * A request to publish.
     *
     * @param versionNo the approved version to make current, or null for the latest approved one (when that version is
     *        already in use, only the audience changes)
     * @param audiences the locations and departments to publish to, at least one and at most 20; a group that is
     *        already published to and is not listed here is taken back
     */
    public record PublishTemplateRequest(Integer versionNo,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid AudiencePick> audiences) {
    }
}
