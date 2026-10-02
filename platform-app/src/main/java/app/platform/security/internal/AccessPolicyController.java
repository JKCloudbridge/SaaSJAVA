package app.platform.security.internal;

import app.platformapi.AccessPolicyView;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.SaveAccessPolicyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access policies (Sprint 7, ADR-0039). Thin: the rules are in {@link AccessPolicyService}. Answered only on an
 * organization host and only for members who manage access; an identifier of another organization is not found.
 */
@RestController
@Tag(name = "Access")
class AccessPolicyController {

    private final AccessPolicyService policies;

    AccessPolicyController(AccessPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping(ApiPaths.ACCESS_POLICIES)
    @Operation(
            operationId = "listAccessPolicies",
            summary = "List the access policies of the organization",
            description = "With the number of members who hold each. For members who manage access. NOT_FOUND on the "
                    + "platform host, FORBIDDEN without the ability.")
    ApiResponse<List<AccessPolicyView>> list() {
        return ApiResponse.of(policies.list());
    }

    @PostMapping(ApiPaths.ACCESS_POLICIES)
    @Operation(
            operationId = "createAccessPolicy",
            summary = "Create an access policy",
            description = "Abilities added to the members it is assigned to; optionally it uses a licence of one type "
                    + "from the organization's pool when assigned. VALIDATION_ERROR for a name in use, an unknown "
                    + "ability or an unknown licence type. Audited.")
    ResponseEntity<ApiResponse<AccessPolicyView>> create(@Valid @RequestBody SaveAccessPolicyRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(policies.create(body)));
    }

    @PutMapping(ApiPaths.ACCESS_POLICIES + "/{policyId}")
    @Operation(
            operationId = "updateAccessPolicy",
            summary = "Change an access policy",
            description = "Takes effect for its members at once. CONFLICT for a licence type change while members hold "
                    + "the policy, or when the change would leave nobody who can manage access. NOT_FOUND for a "
                    + "policy of another organization. Audited.")
    ApiResponse<AccessPolicyView> update(@PathVariable UUID policyId,
            @Valid @RequestBody SaveAccessPolicyRequest body) {
        return ApiResponse.of(policies.update(policyId, body));
    }

    @DeleteMapping(ApiPaths.ACCESS_POLICIES + "/{policyId}")
    @Operation(
            operationId = "deleteAccessPolicy",
            summary = "Remove an access policy",
            description = "CONFLICT while members still hold it. Audited.")
    ResponseEntity<Void> delete(@PathVariable UUID policyId) {
        policies.delete(policyId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
