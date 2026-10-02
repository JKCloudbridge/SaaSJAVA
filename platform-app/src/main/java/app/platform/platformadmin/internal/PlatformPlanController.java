package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;
import static app.platform.identity.PlatformRole.PLATFORM_BILLING;
import static app.platform.identity.PlatformRole.PLATFORM_SUPPORT;

import app.platform.licensing.FeatureView;
import app.platform.licensing.LicenceTypeView;
import app.platform.licensing.PlanView;
import app.platform.licensing.Plans;
import app.platform.sharedkernel.ActorId;
import app.platformapi.AddCatalogueItemRequest;
import app.platformapi.AddLicenceTypeRequest;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CatalogueItem;
import app.platformapi.LicenceTypeItem;
import app.platformapi.PlanInfo;
import app.platformapi.SavePlanRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue part of the platform console (Sprint 6, ADR-0031): plans, licence types and feature keys. Reading is
 * for platform administrators, billing and support; changing is for administrators and billing. Platform host only. The
 * catalogue is the same for every organization.
 */
@RestController
@Tag(name = "Platform plans")
class PlatformPlanController {

    private final PlatformCaller caller;
    private final Plans plans;
    private final PlatformAudit audit;

    PlatformPlanController(PlatformCaller caller, Plans plans, PlatformAudit audit) {
        this.caller = caller;
        this.plans = plans;
        this.audit = audit;
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_BILLING, PLATFORM_SUPPORT})
    @GetMapping(ApiPaths.PLATFORM_PLANS)
    @Operation(
            operationId = "listPlans",
            summary = "The plans",
            description = "Default licence quantities and included features. For platform administrators, billing and "
                    + "support.")
    ApiResponse<List<PlanInfo>> list(Principal principal) {
        return ApiResponse.of(plans.plans().stream().map(PlatformPlanController::info).toList());
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_BILLING})
    @PutMapping(ApiPaths.PLATFORM_PLANS + "/{key}")
    @Operation(
            operationId = "savePlan",
            summary = "Create a plan, or replace the one with this key",
            description = "Organizations keep their pools until their subscription changes plan. For platform "
                    + "administrators and billing.")
    ApiResponse<PlanInfo> save(@PathVariable String key, @Valid @RequestBody SavePlanRequest body,
            Principal principal) {
        UUID actor = caller.person(principal);
        PlanView saved = plans.save(key, body.name(), body.trialDays(), body.licences(), new HashSet<>(body.features()),
                new ActorId(actor));
        audit.done("platform.plan.saved", actor, null, null, "plan", key);
        return ApiResponse.of(info(saved));
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_BILLING, PLATFORM_SUPPORT})
    @GetMapping(ApiPaths.PLATFORM_LICENCE_TYPES)
    @Operation(
            operationId = "listLicenceTypes",
            summary = "The licence types",
            description = "For platform administrators, billing and support.")
    ApiResponse<List<LicenceTypeItem>> licenceTypes(Principal principal) {
        return ApiResponse.of(plans.licenceTypes().stream().map(PlatformPlanController::licenceItem).toList());
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_BILLING})
    @PostMapping(ApiPaths.PLATFORM_LICENCE_TYPES)
    @Operation(
            operationId = "addLicenceType",
            summary = "Add a licence type",
            description = "A SEAT (the right to occupy a seat; profiles belong to one) or an ADD_ON (sold on top, for "
                    + "example the licence of a standard access policy); SEAT when not given. CONFLICT when the key "
                    + "exists. For platform administrators and billing.")
    ResponseEntity<ApiResponse<LicenceTypeItem>> addLicenceType(@Valid @RequestBody AddLicenceTypeRequest body,
            Principal principal) {
        UUID actor = caller.person(principal);
        String kind = body.kind() == null || body.kind().isBlank() ? LicenceTypeView.SEAT : body.kind().strip();
        LicenceTypeView added = plans.addLicenceType(body.key(), body.name(), kind, new ActorId(actor));
        audit.done("platform.licence_type.added", actor, null, null, "licence_type", body.key());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(licenceItem(added)));
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_BILLING, PLATFORM_SUPPORT})
    @GetMapping(ApiPaths.PLATFORM_FEATURES)
    @Operation(
            operationId = "listFeatures",
            summary = "The feature keys",
            description = "For platform administrators, billing and support.")
    ApiResponse<List<CatalogueItem>> features(Principal principal) {
        return ApiResponse.of(plans.features().stream().map(PlatformPlanController::item).toList());
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_BILLING})
    @PostMapping(ApiPaths.PLATFORM_FEATURES)
    @Operation(
            operationId = "addFeature",
            summary = "Add a feature key",
            description = "CONFLICT when the key exists. For platform administrators and billing.")
    ResponseEntity<ApiResponse<CatalogueItem>> addFeature(@Valid @RequestBody AddCatalogueItemRequest body,
            Principal principal) {
        UUID actor = caller.person(principal);
        FeatureView added = plans.addFeature(body.key(), body.name(), new ActorId(actor));
        audit.done("platform.feature.added", actor, null, null, "feature", body.key());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(new CatalogueItem(added.key(),
                added.name())));
    }

    private static PlanInfo info(PlanView plan) {
        return new PlanInfo(plan.key(), plan.name(), plan.trialDays(), plan.licences(),
                plan.features().stream().sorted().toList());
    }

    private static LicenceTypeItem licenceItem(LicenceTypeView type) {
        return new LicenceTypeItem(type.key(), type.name(), type.kind());
    }

    private static CatalogueItem item(FeatureView feature) {
        return new CatalogueItem(feature.key(), feature.name());
    }
}
