package app.platform.data.internal;

import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.RecordFileView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The files of a record (Sprint 17, ADR-0120). Thin: the rules are in {@link FileService}. Answered only on an
 * organization host, for a signed-in member. A file has the permission of its record: reading and downloading need
 * the right to read the record, uploading and deleting the right to change it; a record the member cannot reach, and
 * a file of another record, are "not found". The bytes pass through the application and are sent with headers that
 * keep a browser from running them.
 */
@RestController
@Tag(name = "Files")
class FileController {

    private static final String FILES = ApiPaths.OBJECTS + "/{objectApiName}/records/{id}/files";
    private static final String UNKNOWN = "NOT_FOUND for an object or record that does not exist, that the caller "
            + "may not reach, or that is in the recycle bin: all answer alike. SERVICE_UNAVAILABLE (503, with "
            + "Retry-After) when the object store does not answer; nothing is stored then. ";

    private final FileService files;

    FileController(FileService files) {
        this.files = files;
    }

    @GetMapping(FILES)
    @Operation(
            operationId = "listRecordFiles",
            summary = "The files of a record",
            description = "In the order they were stored. Needs the right to read the record. " + UNKNOWN)
    ApiResponse<List<RecordFileView>> list(@PathVariable String objectApiName, @PathVariable UUID id) {
        return ApiResponse.of(files.list(objectApiName, id).stream().map(FileController::view).toList());
    }

    private static final String NAME_HEADER = "X-File-Name";

    @PostMapping(FILES)
    @Operation(
            operationId = "uploadRecordFile",
            summary = "Store a file on a record",
            description = "The body is the bytes of the file, as they are (not a form), with the length announced "
                    + "(Content-Length; chunked bodies are refused). The name is the header 'X-File-Name', percent-"
                    + "encoded as a URI component (never a query parameter: a request's path and query string are "
                    + "the part of a request that a log at DEBUG prints, and a file's name is a value typed by a "
                    + "person), the type the Content-Type of the request. The server cleans the name (only its "
                    + "last part is kept), decides the type, "
                    + "refuses names and types on the denied list and files that start like a program or a page a "
                    + "browser would run (VALIDATION_ERROR), refuses a file above the largest size "
                    + "(PAYLOAD_TOO_LARGE, checked on the announced length and on the bytes that pass) and a "
                    + "record that has the most files (CONFLICT). The size and the SHA-256 in the answer are those "
                    + "of the bytes that were stored. A failed upload leaves nothing. Needs the right to change "
                    + "the record. Audited by identifiers and size, never by name or content. " + UNKNOWN)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/octet-stream",
            schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<ApiResponse<RecordFileView>> upload(@PathVariable String objectApiName, @PathVariable UUID id,
            @RequestHeader(NAME_HEADER) String encodedName, HttpServletRequest request) throws IOException {
        String name;
        try {
            name = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("name", "Is not a valid percent-encoded name.");
        }
        FileStore.FileRow stored = files.upload(objectApiName, id, name, request.getContentType(),
                request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(view(stored)));
    }

    @GetMapping(FILES + "/{fileId}/content")
    @Operation(
            operationId = "downloadRecordFile",
            summary = "The bytes of a file",
            description = "Sent as an attachment with the type the server decided, with nosniff and a sandbox "
                    + "policy, never to be shown in the page. Authorized on every request: needs the right to read "
                    + "the record. Audited by identifiers. " + UNKNOWN)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The bytes of the file",
            content = @Content(mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")))
    void download(@PathVariable String objectApiName, @PathVariable UUID id, @PathVariable UUID fileId,
            HttpServletResponse response) throws IOException {
        FileService.Download download = files.download(objectApiName, id, fileId);
        FileStore.FileRow file = download.file();
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(file.contentType());
        response.setContentLengthLong(download.stored().length());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try (InputStream bytes = download.stored().bytes()) {
            bytes.transferTo(response.getOutputStream());
        }
    }

    @DeleteMapping(FILES + "/{fileId}")
    @Operation(
            operationId = "deleteRecordFile",
            summary = "Remove a file from a record",
            description = "The file is gone at once; its bytes are removed from the object store by a background "
                    + "pass. Needs the right to change the record. Audited. " + UNKNOWN)
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable UUID id,
            @PathVariable UUID fileId) {
        files.delete(objectApiName, id, fileId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static RecordFileView view(FileStore.FileRow row) {
        return new RecordFileView(row.id(), row.name(), row.contentType(), row.size(), row.sha256(), row.createdAt(),
                row.createdBy());
    }
}
