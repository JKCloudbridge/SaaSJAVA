package app.platform.data.internal;

import app.platform.sharedkernel.storage.ObjectStore;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ImportJobView;
import app.platformapi.StartImportRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSV imports into an object (Sprint 17, ADR-0123). Thin: the rules are in {@link ImportService}. An import is a
 * background job started in two requests (the settings, then the file) and watched until it ends; it belongs to the
 * person who started it.
 */
@RestController
@Tag(name = "Imports")
class ImportController {

    private static final String IMPORTS = ApiPaths.OBJECTS + "/{objectApiName}/imports";
    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that a platform module owns "
            + "or that the caller may not read, and for an import that is not the caller's. FORBIDDEN for a caller "
            + "who may read the object but not create records in it. ";

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping(IMPORTS)
    @Operation(
            operationId = "startImport",
            summary = "Start a CSV import into an object",
            description = "Checks the mapping against the object at once (VALIDATION_ERROR lists every problem). The "
                    + "import waits for its file: send it with the next request. Every row of the file will be "
                    + "written by the same steps as a record written through the API, as the caller, with the "
                    + "caller's permissions: a field the caller may not write, a formula or an auto-number cannot be "
                    + "mapped. CREATE makes a record for every row; UPSERT finds the record by the value of a "
                    + "field flagged as an external identifier (one indexed read) and updates it, or makes it. A "
                    + "link can be written as the other record's identifier or, with a mapping such as "
                    + "'field:externalIdField', as its external identifier. An empty cell leaves a field as it is. "
                    + "A dry run checks every row by the real write and saves nothing. CONFLICT at the most "
                    + "imports an organization can run at once. " + UNKNOWN)
    ResponseEntity<ApiResponse<ImportJobView>> start(@PathVariable String objectApiName,
            @Valid @RequestBody StartImportRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(imports.start(objectApiName, body)));
    }

    @PutMapping(IMPORTS + "/{importId}/file")
    @Operation(
            operationId = "sendImportFile",
            summary = "Send the file of an import",
            description = "The body is the CSV file as it is (UTF-8, the delimiter chosen when the import was "
                    + "started, a header row first, values that hold the delimiter or a line break in double "
                    + "quotes), with its length announced (chunked bodies are refused), at most 20 MiB and 100,000 "
                    + "rows. A file that is not text is refused. CONFLICT when the file was already sent or when "
                    + "another import of the object is waiting or running. The import is queued and runs in the "
                    + "background; watch it with the import endpoint. SERVICE_UNAVAILABLE (503) when the object "
                    + "store does not answer. " + UNKNOWN)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "text/csv",
            schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<ApiResponse<ImportJobView>> file(@PathVariable String objectApiName, @PathVariable UUID importId,
            HttpServletRequest request) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(imports.file(objectApiName, importId, request.getContentLengthLong(),
                        request.getInputStream())));
    }

    @GetMapping(IMPORTS)
    @Operation(
            operationId = "listImports",
            summary = "The caller's latest imports into an object",
            description = "Newest first, at most twenty. " + UNKNOWN)
    ApiResponse<List<ImportJobView>> list(@PathVariable String objectApiName) {
        return ApiResponse.of(imports.list(objectApiName));
    }

    @GetMapping(IMPORTS + "/{importId}")
    @Operation(
            operationId = "getImport",
            summary = "How far an import has got",
            description = "The state, the rows read, made, changed and refused, and why it stopped when it failed. "
                    + UNKNOWN)
    ApiResponse<ImportJobView> get(@PathVariable String objectApiName, @PathVariable UUID importId) {
        return ApiResponse.of(imports.get(objectApiName, importId));
    }

    @PostMapping(IMPORTS + "/{importId}/cancel")
    @Operation(
            operationId = "cancelImport",
            summary = "Stop an import",
            description = "The import stops at the end of the batch it is in; what it already wrote stays (there is "
                    + "no undo). CONFLICT when it is not waiting or running. Audited. " + UNKNOWN)
    ApiResponse<ImportJobView> cancel(@PathVariable String objectApiName, @PathVariable UUID importId) {
        return ApiResponse.of(imports.cancel(objectApiName, importId));
    }

    @GetMapping(IMPORTS + "/{importId}/errors")
    @Operation(
            operationId = "downloadImportErrors",
            summary = "The report of the rows of an import that failed",
            description = "A CSV file: the number of the row (data rows, from 1), the reason, and the row as the file "
                    + "had it, so it can be corrected and sent again. A reason is a message of the write steps and "
                    + "never repeats a value. Removed some days after the import ended. NOT_FOUND when there is no "
                    + "report. " + UNKNOWN)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The report",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    void errors(@PathVariable String objectApiName, @PathVariable UUID importId, HttpServletResponse response)
            throws IOException {
        try (ObjectStore.Stored report = imports.report(objectApiName, importId)) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("text/csv; charset=UTF-8");
            response.setContentLengthLong(report.length());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("import-errors.csv", StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            try (InputStream bytes = report.bytes()) {
                bytes.transferTo(response.getOutputStream());
            }
        }
    }
}
