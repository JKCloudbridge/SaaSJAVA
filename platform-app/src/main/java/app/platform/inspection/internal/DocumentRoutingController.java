package app.platform.inspection.internal;

import app.platform.inspection.internal.DocumentRoutingViews.RoutingView;
import app.platform.inspection.internal.DocumentRoutingViews.SaveRoutingRuleRequest;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rules that route a document to an application by its number (MC1.1, ADR-0259). Thin: the rules are in
 * {@link DocumentRouting}. Answered on an organization host only, for a member who holds {@code numbering.manage}; the
 * Identification and Part A travel in bodies, never in an address.
 */
@RestController
@Tag(name = "Application routing")
class DocumentRoutingController {

    private static final String BASE = ApiPaths.V1 + "/document-routing";

    private final DocumentRouting routing;

    DocumentRoutingController(DocumentRouting routing) {
        this.routing = routing;
    }

    @GetMapping(BASE)
    @Operation(operationId = "listDocumentRouting", summary = "The rules that route a document to an application",
            description = "Needs numbering.manage. Every rule of the organization, the applications a rule can name "
                    + "(only those the organization has whose documents come from Document Management) and the "
                    + "Identification values. A rule names an Identification and, optionally, a Part A (empty means "
                    + "every Part A); the rule of the exact pair wins. A rule decides the application of documents "
                    + "numbered afterwards: it never moves a document that has its number.")
    ApiResponse<RoutingView> list() {
        return ApiResponse.of(routing.view());
    }

    @PutMapping(BASE)
    @Operation(operationId = "saveDocumentRouting", summary = "Make a rule, or change the application of one",
            description = "Needs numbering.manage. Without a version it makes the rule of the combination and is "
                    + "refused (CONFLICT) when one exists; with the version read it changes that rule's application. "
                    + "Audited by the application's key only.")
    ApiResponse<RoutingView> save(@Valid @RequestBody SaveRoutingRuleRequest body) {
        return ApiResponse.of(routing.save(body));
    }

    @DeleteMapping(BASE + "/{ruleId}")
    @Operation(operationId = "removeDocumentRouting", summary = "Remove a rule",
            description = "Needs numbering.manage and the version read. Documents already numbered keep their "
                    + "application. Audited by the application's key only.")
    ApiResponse<RoutingView> remove(@PathVariable UUID ruleId, @RequestParam long version) {
        return ApiResponse.of(routing.remove(ruleId, version));
    }
}
