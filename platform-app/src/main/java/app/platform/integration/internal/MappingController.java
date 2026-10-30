package app.platform.integration.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.DeliveryView;
import app.platformapi.MappingCatalogueView;
import app.platformapi.MappingDryRunRequest;
import app.platformapi.MappingDryRunView;
import app.platformapi.MappingVersionRequest;
import app.platformapi.MappingView;
import app.platformapi.SaveMappingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mappings of a connection and the record of what was sent and received (Sprint 28, ADR-0217, ADR-0220).
 * Organization hosts only: the caller must be a member, hold the abilities and have the plan feature. Definitions and
 * samples travel in bodies, never in addresses, and nothing typed is echoed in an answer or written to a log.
 */
@RestController
@Tag(name = "Integrations")
class MappingController {

    private static final String ROOT = ApiPaths.MAPPINGS;

    private final MappingService mappings;
    private final DeliveryService deliveries;

    MappingController(MappingService mappings, DeliveryService deliveries) {
        this.mappings = mappings;
        this.deliveries = deliveries;
    }

    @GetMapping(ApiPaths.INTEGRATIONS + "/mapping-catalogue")
    @Operation(operationId = "getMappingCatalogue", summary = "What a mapping can be made of",
            description = "Needs integrations.view and mappings.view. The rules, directions, types and conversions "
                    + "the screens draw their choices from.")
    ApiResponse<MappingCatalogueView> catalogue() {
        return ApiResponse.of(mappings.catalogue());
    }

    @GetMapping(ROOT)
    @Operation(operationId = "listMappings", summary = "The mappings of a connection",
            description = "Needs integrations.view and mappings.view. One mapping for each object, with its live "
                    + "version, its draft and the versions that were live before.")
    ApiResponse<List<MappingView>> list(@PathVariable String name) {
        return ApiResponse.of(mappings.list(name));
    }

    @GetMapping(ROOT + "/{object}")
    @Operation(operationId = "getMapping", summary = "The mapping of one object",
            description = "Needs integrations.view and mappings.view.")
    ApiResponse<MappingView> get(@PathVariable String name, @PathVariable String object) {
        return ApiResponse.of(mappings.get(name, object));
    }

    @PutMapping(ROOT + "/{object}")
    @Operation(operationId = "saveMappingDraft", summary = "Save the draft of a mapping",
            description = "Needs integrations.view, mappings.view and mappings.manage. Checks the definition against "
                    + "the object as it is now and stores it as the draft; nothing is sent or received until it is "
                    + "published. The first save for an object makes the mapping and names its entity.")
    ApiResponse<MappingView> save(@PathVariable String name, @PathVariable String object,
            @Valid @RequestBody SaveMappingRequest body) {
        return ApiResponse.of(mappings.saveDraft(name, object, body));
    }

    @DeleteMapping(ROOT + "/{object}/draft")
    @Operation(operationId = "discardMappingDraft", summary = "Discard the draft of a mapping",
            description = "Needs integrations.view, mappings.view and mappings.manage and the version of the draft "
                    + "read.")
    ApiResponse<Boolean> discard(@PathVariable String name, @PathVariable String object,
            @RequestParam long version) {
        mappings.discardDraft(name, object, version);
        return ApiResponse.of(Boolean.TRUE);
    }

    @PostMapping(ROOT + "/{object}/publish")
    @Operation(operationId = "publishMapping", summary = "Make the draft live",
            description = "Needs integrations.view, mappings.view and mappings.manage and the version of the draft "
                    + "read. Checked again against the object as it is now and against the scope of the connection. "
                    + "The version that was live ends; events already under way finish under the version they began "
                    + "with.")
    ApiResponse<MappingView> publish(@PathVariable String name, @PathVariable String object,
            @Valid @RequestBody MappingVersionRequest body) {
        return ApiResponse.of(mappings.publish(name, object, body.version()));
    }

    @PostMapping(ROOT + "/{object}/stop")
    @Operation(operationId = "stopMapping", summary = "Stop the live version",
            description = "Needs integrations.view, mappings.view and mappings.manage. Nothing is sent or received "
                    + "for the object until a draft is published again.")
    ApiResponse<MappingView> stop(@PathVariable String name, @PathVariable String object) {
        return ApiResponse.of(mappings.stop(name, object));
    }

    @PostMapping(ROOT + "/{object}/dry-run")
    @Operation(operationId = "tryMapping", summary = "Try a definition on a sample",
            description = "Needs integrations.view, mappings.view and mappings.manage. The real mapper works on the "
                    + "sample the person typed; nothing is stored, sent, logged or audited.")
    ApiResponse<MappingDryRunView> dryRun(@PathVariable String name, @PathVariable String object,
            @Valid @RequestBody MappingDryRunRequest body) {
        return ApiResponse.of(mappings.dryRun(name, object, body));
    }

    @GetMapping(ApiPaths.DELIVERIES)
    @Operation(operationId = "listDeliveries", summary = "What was sent and received",
            description = "Needs integrations.view and mappings.view. The newest first, at most 100. Identifiers, a "
                    + "stable reason and the platform's own sentence for it: never a record value or a payload.")
    ApiResponse<List<DeliveryView>> deliveries(@PathVariable String name,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.of(deliveries.recent(name, limit));
    }
}
