package app.platform.security.internal;

import app.platformapi.AbilityInfo;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.LicenceTypeItem;
import app.platformapi.ProfileView;
import app.platformapi.SaveProfileRequest;
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
 * Profiles and the list of abilities (Sprint 7, ADR-0039). Thin: the rules are in {@link ProfileService}. Answered only
 * on an organization host; the organization is the host, and a profile identifier of another organization is simply not
 * found. Who may do what is decided by the abilities of the caller, on the server.
 */
@RestController
@Tag(name = "Access")
class ProfileController {

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping(ApiPaths.ABILITIES)
    @Operation(
            operationId = "listAbilities",
            summary = "The abilities the platform knows",
            description = "What a profile, an access policy or an individual grant can hold. For members who manage "
                    + "access, invite members or see members. NOT_FOUND on the platform host, FORBIDDEN without one "
                    + "of those abilities.")
    ApiResponse<List<AbilityInfo>> abilities() {
        return ApiResponse.of(profiles.abilities());
    }

    @GetMapping(ApiPaths.LICENCE_TYPES)
    @Operation(
            operationId = "listLicenceTypes",
            summary = "The licence types a profile or access policy can use",
            description = "The platform's catalogue of licence types, for the setup screens. For members who manage "
                    + "access, invite members or see members. NOT_FOUND on the platform host, FORBIDDEN without "
                    + "one of those abilities.")
    ApiResponse<List<LicenceTypeItem>> licenceTypes() {
        return ApiResponse.of(profiles.licenceTypes());
    }

    @GetMapping(ApiPaths.PROFILES)
    @Operation(
            operationId = "listProfiles",
            summary = "List the profiles of the organization",
            description = "With the number of members who hold each. For members who manage access, invite members or "
                    + "see members. NOT_FOUND on the platform host, FORBIDDEN without one of those abilities.")
    ApiResponse<List<ProfileView>> list() {
        return ApiResponse.of(profiles.list());
    }

    @PostMapping(ApiPaths.PROFILES)
    @Operation(
            operationId = "createProfile",
            summary = "Create a profile",
            description = "A named base set of abilities that belongs to one licence type. VALIDATION_ERROR for a "
                    + "name in use, an unknown ability or an unknown licence type. For members who manage access; "
                    + "audited.")
    ResponseEntity<ApiResponse<ProfileView>> create(@Valid @RequestBody SaveProfileRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(profiles.create(body)));
    }

    @PutMapping(ApiPaths.PROFILES + "/{profileId}")
    @Operation(
            operationId = "updateProfile",
            summary = "Change a profile",
            description = "Takes effect for its members at once. CONFLICT for the administrator profile, for a licence "
                    + "type change while members hold the profile, or when the change would leave nobody who can "
                    + "manage access. NOT_FOUND for a profile of another organization. Audited.")
    ApiResponse<ProfileView> update(@PathVariable UUID profileId, @Valid @RequestBody SaveProfileRequest body) {
        return ApiResponse.of(profiles.update(profileId, body));
    }

    @DeleteMapping(ApiPaths.PROFILES + "/{profileId}")
    @Operation(
            operationId = "deleteProfile",
            summary = "Remove a profile",
            description = "CONFLICT for a system profile, the default profile or a profile members still hold. "
                    + "Audited.")
    ResponseEntity<Void> delete(@PathVariable UUID profileId) {
        profiles.delete(profileId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(ApiPaths.PROFILES + "/{profileId}/default")
    @Operation(
            operationId = "makeProfileDefault",
            summary = "Make a profile the default for new members",
            description = "CONFLICT for the administrator profile. Audited.")
    ResponseEntity<Void> makeDefault(@PathVariable UUID profileId) {
        profiles.makeDefault(profileId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
