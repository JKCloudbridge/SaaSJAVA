package app.platform.inspection.internal;

import app.platform.inspection.internal.PlantViews.PlantPublicationState;
import app.platform.inspection.internal.PlantViews.PublishPlantsRequest;
import app.platform.inspection.internal.PlantViews.ReviewerChoices;
import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of publishing a document to its plants (MC2A, ADR-0260). Thin: the rules are in
 * {@link PlantPublishRule} and {@link PlantPublishing}. Answered only on an organization host, for a signed-in member;
 * the address names the document, never the organization.
 */
@RestController
@Tag(name = "Templates")
class PlantPublicationController {

    private static final String BASE = ApiPaths.V1 + "/templates/{templateId}/plants";
    private static final String APPLICATION = "The application whose records are meant (its API name). It chooses "
            + "among the sets of records only and proves nothing: NOT_FOUND, in the same words, for an application "
            + "that does not exist, one the organization does not have and the platform host.";
    private static final String SEES = "NOT_FOUND for a document the caller cannot see, for one that does not exist "
            + "and on the platform host (all alike). ";
    private static final String WHO = "Allowed to the person who made the document and to a member who holds the "
            + "ability to publish documents to plants (FORBIDDEN otherwise); CONFLICT for a document that does not "
            + "belong to an application that publishes to plants. ";

    private final PlantPublishing publishing;
    private final RecordSets sets;

    PlantPublicationController(PlantPublishing publishing, RecordSets sets) {
        this.publishing = publishing;
        this.sets = sets;
    }

    @GetMapping(BASE)
    @Operation(
            operationId = "getTemplatePlants",
            summary = "The plants a document is published to and whether the caller may change them",
            description = "available: the document belongs to an application that publishes to plants. canPublish: "
                    + "the caller made it (or holds the ability), a Revision is in use and it is not retired; the flag "
                    + "only hides a button, each act is decided again. The plants with their reviewers are told only "
                    + "to a caller who may publish. " + SEES)
    ApiResponse<PlantPublicationState> state(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(publishing.state(sets.require(application), templateId));
    }

    @GetMapping(BASE + "/options")
    @Operation(
            operationId = "templatePlantOptions",
            summary = "The plants a document can be published to",
            description = "Every location of the organization's list with whether a group of plant heads is set up for "
                    + "it; a location without one cannot be published to. " + WHO + SEES)
    ApiResponse<AudienceOptionsView> options(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(publishing.options(sets.require(application), templateId));
    }

    @PostMapping(BASE + "/reviewer-choices")
    @Operation(
            operationId = "templatePlantReviewerChoices",
            summary = "The people and groups a reviewer can be chosen from",
            description = "At most 20 active members and 20 public groups whose name contains the text; a person "
                    + "needs the text to match, so the answer cannot be used to list the directory. The text is "
                    + "typed by a person, so it travels in the body and is never logged. " + WHO + SEES)
    ApiResponse<ReviewerChoices> reviewerChoices(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody PlantViews.ReviewerSearch search) {
        return ApiResponse.of(publishing.reviewerChoices(sets.require(application), templateId, search.text()));
    }

    @PutMapping(BASE)
    @Operation(
            operationId = "publishTemplateToPlants",
            summary = "Publish a document to its plants and choose the reviewers of each",
            description = "Sets the whole: a plant listed and not published to yet is added (its plant heads can read "
                    + "the document and are told), a plant listed with other reviewers has them changed, and a plant "
                    + "published to and not listed is taken back (an empty list takes the document back from every "
                    + "plant). Every plant must have a group of plant heads with members and at least one reviewer; a "
                    + "reviewer is an active member or a public group. Every plant is checked before anything is "
                    + "shared: VALIDATION_ERROR with plants[i] or plants[i].reviewers[j] naming the place. A change "
                    + "of reviewers never reaches a review that is running. CONFLICT for a retired document and for "
                    + "one with no Revision in use. Audited by counts, never by a name. " + WHO + SEES)
    ApiResponse<PlantPublicationState> publish(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody PublishPlantsRequest request) {
        return ApiResponse.of(publishing.publish(sets.require(application), templateId, request));
    }
}
