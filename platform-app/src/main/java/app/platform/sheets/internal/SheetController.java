package app.platform.sheets.internal;

import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ErrorCode;
import app.platformapi.NewSheetVersionRequest;
import app.platformapi.SheetVersionSummaryView;
import app.platformapi.SheetVersionView;
import app.platformapi.SheetVersionsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The versions of a sheet (FA3, ADR-0186). Thin: the rules are in {@link SheetVersions}. Answered only on an
 * organization host, for a signed-in member who may read the record the sheet belongs to. The address names the record
 * ({@code ownerObject} and {@code ownerId}), never the organization.
 */
@RestController
@Tag(name = "Sheets")
class SheetController {

    private static final String VERSIONS = ApiPaths.SHEETS + "/{ownerObject}/{ownerId}/versions";
    private static final String UNKNOWN = "NOT_FOUND for an object that cannot own a sheet, for a record that does not "
            + "exist and for a record the caller may not read (all three answer alike). A sheet has no permission of "
            + "its own: reading it needs the right to read the record and changing it the right to update the "
            + "record. ";

    private final SheetVersions versions;
    private final SheetProperties properties;

    SheetController(SheetVersions versions, SheetProperties properties) {
        this.versions = versions;
        this.properties = properties;
    }

    @GetMapping(VERSIONS)
    @Operation(
            operationId = "listSheetVersions",
            summary = "The versions of a sheet the caller may see",
            description = "Newest first, without their grids (a list never reads a grid). A caller who may only read "
                    + "the record sees the versions that are in use or were (ACTIVE, SUPERSEDED, RETIRED); a caller "
                    + "who may change it sees every version, drafts included. canChange says whether the screen may "
                    + "offer to start or save a draft; every request that follows is decided again. " + UNKNOWN)
    ApiResponse<SheetVersionsView> list(@PathVariable String ownerObject, @PathVariable UUID ownerId) {
        return ApiResponse.of(versions.list(ownerObject, ownerId));
    }

    @PostMapping(VERSIONS)
    @Operation(
            operationId = "createSheetVersion",
            summary = "Start a new draft version of a sheet",
            description = "The first version is blank and names the shape of the sheet (dimension, and optionally "
                    + "rowsMode); every later one is a copy of the version named by basedOn, or of the active version "
                    + "(the latest when none is active), and keeps the shape: another shape is VALIDATION_ERROR. "
                    + "Versions are numbered automatically and a number is never reused. There is at most one draft "
                    + "at a time: CONFLICT names the number of the existing one, and two requests that arrive "
                    + "together get one draft and one answer. CONFLICT also at the most versions a sheet may have. "
                    + "FORBIDDEN for a caller who may read the record but not update it. Audited by the number of "
                    + "the version and the shape, never by content. " + UNKNOWN)
    ResponseEntity<ApiResponse<SheetVersionSummaryView>> create(@PathVariable String ownerObject,
            @PathVariable UUID ownerId, @Valid @RequestBody(required = false) NewSheetVersionRequest body) {
        NewSheetVersionRequest request = body == null ? new NewSheetVersionRequest(null, null, null) : body;
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(versions.create(ownerObject, ownerId, request)));
    }

    @GetMapping(VERSIONS + "/{versionNo}")
    @Operation(
            operationId = "getSheetVersion",
            summary = "One version of a sheet with its grid",
            description = "The only call that reads a grid. NOT_FOUND also for a version that does not exist and for "
                    + "a version the caller may not see (a caller who may only read the record sees ACTIVE, "
                    + "SUPERSEDED and RETIRED versions; a draft or a version in review answers like a version that "
                    + "does not exist). " + UNKNOWN)
    ResponseEntity<ApiResponse<SheetVersionView>> get(@PathVariable String ownerObject, @PathVariable UUID ownerId,
            @PathVariable int versionNo) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(versions.get(ownerObject, ownerId, versionNo)));
    }

    @PutMapping(value = VERSIONS + "/{versionNo}/grid", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveSheetGrid",
            summary = "Save the grid of a draft version",
            description = "The body is the grid itself (the schema SheetGrid of this document), as JSON, and nothing "
                    + "else; the row version that was read goes in the version parameter. The size is checked "
                    + "before anything is parsed, from the length the client announces and again on the bytes read "
                    + "(so a body without a length is bounded too): PAYLOAD_TOO_LARGE above the limit of the "
                    + "organization. Then the grid is checked in a fixed order and the first stage with a problem "
                    + "answers alone, with every place of that stage under grid: VALIDATION_ERROR. A grid is stored "
                    + "in a canonical form, so the same grid always has the same hash. CONCURRENT_MODIFICATION (409) "
                    + "when the draft has changed since it was read, and nothing changes; CONFLICT for a version "
                    + "that is no longer a draft. FORBIDDEN for a caller who may read the record but not update it. "
                    + "Audited by the number, the size, the hash and counts, never by content. " + UNKNOWN)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            schema = @Schema(ref = "#/components/schemas/SheetGrid")))
    ApiResponse<SheetVersionSummaryView> save(@PathVariable String ownerObject, @PathVariable UUID ownerId,
            @PathVariable int versionNo, @RequestParam long version, HttpServletRequest request) {
        byte[] grid = readBounded(request);
        return ApiResponse.of(versions.save(ownerObject, ownerId, versionNo, version, grid));
    }

    /**
     * The bytes of the body, at most the size limit: the announced length is checked first by {@link SheetBodyLimit},
     * and here the bytes themselves are counted, so a client that announced less than it sends, or announced nothing
     * (a chunked body), cannot make the server hold more.
     */
    private byte[] readBounded(HttpServletRequest request) {
        int most = properties.limits().maxBytes();
        try {
            byte[] bytes = request.getInputStream().readNBytes(most + 1);
            if (bytes.length > most) {
                throw tooLarge(most);
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ApiException tooLarge(int most) {
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The grid is larger than the allowed size of " + most
                + " bytes.");
    }
}
