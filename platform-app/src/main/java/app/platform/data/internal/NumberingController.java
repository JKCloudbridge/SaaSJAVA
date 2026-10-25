package app.platform.data.internal;

import app.platformapi.AdvanceNumberingCounterRequest;
import app.platformapi.ApiResponse;
import app.platformapi.ApiPaths;
import app.platformapi.NumberingPreviewView;
import app.platformapi.NumberingSettingView;
import app.platformapi.NumberingSettingsView;
import app.platformapi.PreviewNumberingRequest;
import app.platformapi.ResetNumberingFormatRequest;
import app.platformapi.SaveNumberingFormatRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The organization's numbering settings. Values and candidate formats travel in bodies, never query strings. */
@RestController
@Tag(name = "Numbering")
class NumberingController {

    private static final String ROOT = ApiPaths.NUMBERING;
    private final NumberingManagement management;

    NumberingController(NumberingManagement management) {
        this.management = management;
    }

    @GetMapping(ROOT)
    @Operation(operationId = "listNumberingSettings", summary = "The organization's numbering settings",
            description = "Needs numbering.manage. Lists shipped and organization formats and switched-off categories.")
    ApiResponse<NumberingSettingsView> list() {
        return ApiResponse.of(management.list());
    }

    @PutMapping(ROOT + "/{seriesKey}/formats")
    @Operation(operationId = "saveNumberingFormat", summary = "Save a number format for this organization",
            description = "Needs numbering.manage and the version read. Changes future numbers only; never resets "
                    + "a counter. The closed format is checked on the server. Audited by series, setting and count.")
    ApiResponse<NumberingSettingView> save(@PathVariable String seriesKey,
            @Valid @RequestBody SaveNumberingFormatRequest body) {
        return ApiResponse.of(management.save(seriesKey, body));
    }

    @PostMapping(ROOT + "/{seriesKey}/formats/reset")
    @Operation(operationId = "resetNumberingFormat", summary = "Go back to the platform's format",
            description = "Needs numbering.manage and the version read. Ends this organization's override of one "
                    + "setting; future numbers use the platform's format again. Counters and numbers given are "
                    + "unchanged.")
    ApiResponse<NumberingSettingsView> reset(@PathVariable String seriesKey,
            @Valid @RequestBody ResetNumberingFormatRequest body) {
        return ApiResponse.of(management.reset(seriesKey, body));
    }

    @PostMapping(ROOT + "/{seriesKey}/preview")
    @Operation(operationId = "previewNumbering", summary = "Preview a number without reserving it",
            description = "Needs numbering.manage. A candidate format is optional; without one, uses the current "
                    + "effective format. Reads the count without advancing it. No format, code or number is logged.")
    ApiResponse<NumberingPreviewView> preview(@PathVariable String seriesKey,
            @Valid @RequestBody PreviewNumberingRequest body) {
        return ApiResponse.of(management.preview(seriesKey, body));
    }

    @PostMapping(ROOT + "/{seriesKey}/counter")
    @Operation(operationId = "advanceNumberingCounter", summary = "Raise a pair's next count",
            description = "Needs numbering.manage. Uses the current effective format; never lowers a counter. "
                    + "Serialized against allocations and format changes. Numbers already given are unchanged.")
    ApiResponse<NumberingPreviewView> advance(@PathVariable String seriesKey,
            @Valid @RequestBody AdvanceNumberingCounterRequest body) {
        return ApiResponse.of(management.advance(seriesKey, body));
    }
}
