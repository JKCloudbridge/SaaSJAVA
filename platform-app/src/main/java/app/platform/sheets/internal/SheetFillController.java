package app.platform.sheets.internal;

import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ErrorCode;
import app.platformapi.SheetFillSummaryView;
import app.platformapi.SheetFillView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cells of a fill-in (FF1, ADR-0192). Thin: the rules are in {@link SheetFills}. Answered only on an organization
 * host, for a signed-in member who may read the header record the fill-in belongs to. The address names that record
 * ({@code fillObject} and {@code fillId}), never the organization.
 *
 * <p>Starting a fill-in and sending it for review are not here: the module that owns the header does them (the start
 * is its own act and the review is the ordinary approval endpoint), so a fill-in can never be made out of nothing by a
 * request to this controller.
 */
@RestController
@Tag(name = "Sheet fill-ins")
class SheetFillController {

    private static final String FILL = ApiPaths.SHEET_FILLS + "/{fillObject}/{fillId}";
    private static final String UNKNOWN = "NOT_FOUND for an object that has no fill-ins, for a fill-in that does not "
            + "exist and for a header record the caller may not read (all three answer alike). A fill-in has no "
            + "permission of its own: reading it needs the right to read the header record and changing it the "
            + "right to update the header record. ";

    private final SheetFills fills;
    private final FillProperties properties;

    SheetFillController(SheetFills fills, FillProperties properties) {
        this.fills = fills;
        this.properties = properties;
    }

    @GetMapping(FILL)
    @Operation(
            operationId = "getSheetFill",
            summary = "One fill-in with the sheet version it was made on and its cells",
            description = "The call that reads the cells. The sheet is the version the fill-in is pinned to for ever "
                    + "(versionStatus says whether it is still the one in use), so the fill-in shows against the "
                    + "structure it was made on whatever happened to the sheet since. canEdit says whether the "
                    + "screen may offer to type; correction says that typing would be a counted correction of a "
                    + "fill-in that was sent. A person asked to review the fill-in reads it even without the right "
                    + "to read the header. " + UNKNOWN)
    ResponseEntity<ApiResponse<SheetFillView>> get(@PathVariable String fillObject, @PathVariable UUID fillId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(fills.get(fillObject, fillId)));
    }

    @PutMapping(value = FILL + "/cells", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "saveSheetFillCells",
            summary = "Save the cells of a fill-in",
            description = "The body is the cells document itself (formatVersion and rows, each row with an id and "
                    + "its cells keyed by the identifier of the column), as JSON, and nothing else; the row version "
                    + "that was read goes in the version parameter. The size is checked before anything is parsed, "
                    + "from the length the client announces and again on the bytes read: PAYLOAD_TOO_LARGE above "
                    + "the limit. Then the cells are checked in a fixed order and the first stage with a problem "
                    + "answers alone, with every place of that stage under cells: VALIDATION_ERROR. Only input "
                    + "cells accept a value, each value must fit the type of its column, and rows can be added "
                    + "only where the sheet allows it. A draft may be saved half done. CONCURRENT_MODIFICATION "
                    + "(409) when the fill-in has changed since it was read, and nothing changes. A fill-in that "
                    + "was sent for review can be changed only as a counted correction by a caller who may "
                    + "correct it (FORBIDDEN otherwise), and stays complete. CONFLICT when the sheet was retired. "
                    + "Audited by the size, the hash and counts, never by content. " + UNKNOWN)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            schema = @Schema(type = "object")))
    ApiResponse<SheetFillSummaryView> save(@PathVariable String fillObject, @PathVariable UUID fillId,
            @RequestParam long version, HttpServletRequest request) {
        return ApiResponse.of(fills.save(fillObject, fillId, version, readBounded(request)));
    }

    /**
     * The bytes of the body, at most the size limit: the announced length is checked first by {@link SheetBodyLimit},
     * and here the bytes themselves are counted, so a client that announced less than it sends, or announced nothing
     * (a chunked body), cannot make the server hold more.
     */
    private byte[] readBounded(HttpServletRequest request) {
        int most = properties.maxBytes();
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
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The fill-in is larger than the allowed size of " + most
                + " bytes.");
    }
}
