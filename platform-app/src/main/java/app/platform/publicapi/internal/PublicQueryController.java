package app.platform.publicapi.internal;

import app.platform.data.QueryResult;
import app.platform.data.Records;
import app.platform.publicapi.PublicQueryRequest;
import app.platform.publicapi.PublicRecord;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.Pagination;
import app.platform.sharedkernel.access.ApiScopes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asks a question of the records of an object (Sprint 30, ADR-0245). Thin: the question is turned into the closed
 * query model of the platform and answered by {@link Records#query}, which checks it against the metadata and the
 * caller's rights, plans it and runs it; only the records the caller may reach are searched.
 */
@RestController
@Tag(name = "Records")
class PublicQueryController {

    private final Records records;

    PublicQueryController(Records records) {
        this.records = records;
    }

    @PostMapping(ApiPaths.PUBLIC_OBJECTS + "/{object}/records/query")
    @SecurityRequirements({@SecurityRequirement(name = PublicApiConfiguration.BEARER),
        @SecurityRequirement(name = PublicApiConfiguration.CLIENT_CREDENTIALS, scopes = ApiScopes.RECORDS_READ)})
    @Operation(
            operationId = "queryRecords",
            summary = "Ask a question of the records of an object: filter, order, fields, links, one page at a time",
            description = "The body is a query model, never SQL: a tree of conditions (AND, OR and comparisons by the "
                    + "kind of field; at most 50 nodes), at most two sort keys, the fields to bring and the links to "
                    + "follow (one level). Names are looked up in the metadata and values are data. A field the "
                    + "caller may not read is answered like a field that does not exist, in a filter, an order, a "
                    + "field list or a link, so the answer never reveals what the caller may not see; records leave "
                    + "out such fields. The first stage that finds problems answers VALIDATION_ERROR with all its "
                    + "problems and their places, in the order: shape, fields, operators and values. A filter or an "
                    + "order on a field without a complete index is allowed only on an object with few records "
                    + "(20,000 unless the deployment says otherwise); on a larger one the answer is VALIDATION_ERROR "
                    + "at `query` saying which field to flag as indexed, never a slow request. A formula field can "
                    + "be used in a filter or an order while its stored results are current. Records without a value "
                    + "come last in both directions; ties are broken by the identifier. Sorting by `updatedAt` is not "
                    + "offered. There is no total: the page says whether another follows and gives a cursor that is "
                    + "good only for the same query; a page may be short and still have a cursor, only no cursor is "
                    + "the end. Only the records the caller may reach are searched. NOT_FOUND for an object that "
                    + "does not exist, that the platform itself owns, that belongs to another organization or that "
                    + "the caller may not read: all alike.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                        description = "One page of records and the pagination."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                        description = "VALIDATION_ERROR with the problems and their places, or MALFORMED_REQUEST."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413",
                        description = "PAYLOAD_TOO_LARGE: the body is over 1 MiB."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                        description = "UNSUPPORTED_MEDIA_TYPE: send application/json.")})
    ResponseEntity<ApiPageResponse<PublicRecord>> query(@PathVariable("object") String object,
            @Valid @RequestBody PublicQueryRequest body) {
        QueryResult result = records.query(object, PublicRecordMapper.query(body));
        List<PublicRecord> items = result.records().stream().map(PublicRecordMapper::record).toList();
        Pagination pagination = result.hasMore() ? Pagination.more(result.limit(), result.nextCursor())
                : Pagination.last(result.limit());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiPageResponse.of(items, pagination));
    }
}
