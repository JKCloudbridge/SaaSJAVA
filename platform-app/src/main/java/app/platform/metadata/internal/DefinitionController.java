package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ApplicationView;
import app.platformapi.CreateApplicationRequest;
import app.platformapi.CreateCustomPageRequest;
import app.platformapi.CreateTabRequest;
import app.platformapi.CustomPageView;
import app.platformapi.TabView;
import app.platformapi.UpdateCustomPageRequest;
import app.platformapi.UpdateTabRequest;
import app.platformapi.CreateLayoutRequest;
import app.platformapi.CreateRecordPageRequest;
import app.platformapi.LayoutView;
import app.platformapi.RecordPageView;
import app.platformapi.UpdateApplicationRequest;
import app.platformapi.UpdateLayoutRequest;
import app.platformapi.UpdateRecordPageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * Page layouts and record pages of an object and the applications of the organization (Sprint 12, ADR-0069). Thin:
 * the rules are in {@link DefinitionService}. Answered only on an organization host; reading needs
 * {@code metadata.view}; a change is made live at once, which is a publication of one change, so it needs
 * {@code metadata.manage} and {@code metadata.publish} (ADR-0068). Several changes that belong together go through a
 * change set instead. The reason for each refusal is in the words of the error; a definition that does not fit its
 * schema is refused with the place that is wrong.
 */
@RestController
@Tag(name = "Metadata")
class DefinitionController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String LAYOUTS = ApiPaths.METADATA_OBJECTS + "/{objectApiName}/layouts";
    private static final String PAGES = ApiPaths.METADATA_OBJECTS + "/{objectApiName}/record-pages";
    private static final String APPLICATIONS = ApiPaths.METADATA_APPLICATIONS;
    private static final String TABS = ApiPaths.METADATA_TABS;
    private static final String CUSTOM_PAGES = ApiPaths.METADATA_CUSTOM_PAGES;
    private static final String SCHEMA_NOTE = "The definition is checked against the schema of the same name in "
            + "components.schemas of this document; VALIDATION_ERROR names every place that is wrong, for example "
            + "definition.sections[1].fields[0].behaviour.";

    private final MetadataAdministration administration;
    private final DefinitionService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    DefinitionController(MetadataAdministration administration, DefinitionService service,
            MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    // ---- page layouts ----

    @GetMapping(LAYOUTS)
    @Operation(
            operationId = "listLayouts",
            summary = "The page layouts of an object",
            description = "For members who may view objects and fields. NOT_FOUND for an object the organization "
                    + "does not have.")
    ApiResponse<List<LayoutView>> listLayouts(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.layout.list", Ability.METADATA_VIEW,
                caller -> service.layouts(objectApiName)));
    }

    @GetMapping(LAYOUTS + "/{layoutApiName}")
    @Operation(
            operationId = "getLayout",
            summary = "One page layout",
            description = "NOT_FOUND for a layout the object does not have.")
    ApiResponse<LayoutView> getLayout(@PathVariable String objectApiName, @PathVariable String layoutApiName) {
        return ApiResponse.of(administration.run("metadata.layout.get", Ability.METADATA_VIEW,
                caller -> service.layout(objectApiName, layoutApiName)));
    }

    @PostMapping(LAYOUTS)
    @Operation(
            operationId = "createLayout",
            summary = "Add a page layout to an object",
            description = "Live at once, as a release of one change. The name is turned into the permanent API name "
                    + "with the ending __c. " + SCHEMA_NOTE + " CONFLICT for an object whose records belong to the "
                    + "platform, at the limit, or when the result would break a dependency (a required field the "
                    + "layout does not show). Audited.")
    ResponseEntity<ApiResponse<LayoutView>> createLayout(@PathVariable String objectApiName,
            @Valid @RequestBody CreateLayoutRequest body) {
        LayoutView created = administration.runAll("metadata.layout.create", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.CREATE_LAYOUT, objectApiName, null, body), LayoutView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(LAYOUTS + "/{layoutApiName}")
    @Operation(
            operationId = "updateLayout",
            summary = "Change a page layout",
            description = "Live at once, as a release of one change. The API name and the object never change. "
                    + SCHEMA_NOTE + " CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.")
    ApiResponse<LayoutView> updateLayout(@PathVariable String objectApiName, @PathVariable String layoutApiName,
            @Valid @RequestBody UpdateLayoutRequest body) {
        return ApiResponse.of(administration.runAll("metadata.layout.update", LIVE, caller -> lifecycle.applyNow(
                caller, codec.now(Change.Kind.UPDATE_LAYOUT, objectApiName, layoutApiName, body), LayoutView.class)));
    }

    @DeleteMapping(LAYOUTS + "/{layoutApiName}")
    @Operation(
            operationId = "deleteLayout",
            summary = "Remove a page layout",
            description = "Live at once, as a release of one change. CONFLICT, naming what still uses it, when a "
                    + "record type, record page or assignment depends on it. Audited.")
    ResponseEntity<Void> deleteLayout(@PathVariable String objectApiName, @PathVariable String layoutApiName) {
        administration.runAll("metadata.layout.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_LAYOUT, objectApiName, layoutApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    // ---- record pages ----

    @GetMapping(PAGES)
    @Operation(
            operationId = "listRecordPages",
            summary = "The record pages of an object",
            description = "For members who may view objects and fields. NOT_FOUND for an object the organization "
                    + "does not have.")
    ApiResponse<List<RecordPageView>> listRecordPages(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.recordpage.list", Ability.METADATA_VIEW,
                caller -> service.recordPages(objectApiName)));
    }

    @GetMapping(PAGES + "/{pageApiName}")
    @Operation(
            operationId = "getRecordPage",
            summary = "One record page",
            description = "NOT_FOUND for a record page the object does not have.")
    ApiResponse<RecordPageView> getRecordPage(@PathVariable String objectApiName,
            @PathVariable String pageApiName) {
        return ApiResponse.of(administration.run("metadata.recordpage.get", Ability.METADATA_VIEW,
                caller -> service.recordPage(objectApiName, pageApiName)));
    }

    @PostMapping(PAGES)
    @Operation(
            operationId = "createRecordPage",
            summary = "Add a record page to an object",
            description = "Live at once, as a release of one change. " + SCHEMA_NOTE + " Conditions that decide "
                    + "when a component or action is shown are closed data (a field compared with a value, or an "
                    + "ability, combined with all/any to two levels); there is no expression text. CONFLICT when "
                    + "the layout or a field it names does not exist or a related list does not follow a "
                    + "relationship. Audited.")
    ResponseEntity<ApiResponse<RecordPageView>> createRecordPage(@PathVariable String objectApiName,
            @Valid @RequestBody CreateRecordPageRequest body) {
        RecordPageView created = administration.runAll("metadata.recordpage.create", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.CREATE_RECORD_PAGE, objectApiName, null, body), RecordPageView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(PAGES + "/{pageApiName}")
    @Operation(
            operationId = "updateRecordPage",
            summary = "Change a record page",
            description = "Live at once, as a release of one change. The API name and the object never change. "
                    + SCHEMA_NOTE + " CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.")
    ApiResponse<RecordPageView> updateRecordPage(@PathVariable String objectApiName,
            @PathVariable String pageApiName, @Valid @RequestBody UpdateRecordPageRequest body) {
        return ApiResponse.of(administration.runAll("metadata.recordpage.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_RECORD_PAGE, objectApiName, pageApiName, body),
                        RecordPageView.class)));
    }

    @DeleteMapping(PAGES + "/{pageApiName}")
    @Operation(
            operationId = "deleteRecordPage",
            summary = "Remove a record page",
            description = "Live at once, as a release of one change. CONFLICT, naming the application, when a "
                    + "navigation item opens it. Audited.")
    ResponseEntity<Void> deleteRecordPage(@PathVariable String objectApiName, @PathVariable String pageApiName) {
        administration.runAll("metadata.recordpage.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_RECORD_PAGE, objectApiName, pageApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    // ---- applications ----

    @GetMapping(APPLICATIONS)
    @Operation(
            operationId = "listApplicationDefinitions",
            summary = "The applications of the organization, as defined",
            description = "For members who may view objects and fields: the whole definition, including the "
                    + "profiles that see each application. A member's own menu is GET /api/v1/applications.")
    ApiResponse<List<ApplicationView>> listApplications() {
        return ApiResponse.of(administration.run("metadata.application.list", Ability.METADATA_VIEW,
                caller -> service.applications()));
    }

    @GetMapping(APPLICATIONS + "/{applicationApiName}")
    @Operation(
            operationId = "getApplicationDefinition",
            summary = "One application, as defined",
            description = "NOT_FOUND for an application the organization does not have.")
    ApiResponse<ApplicationView> getApplication(@PathVariable String applicationApiName) {
        return ApiResponse.of(administration.run("metadata.application.get", Ability.METADATA_VIEW,
                caller -> service.application(applicationApiName)));
    }

    @PostMapping(APPLICATIONS)
    @Operation(
            operationId = "createApplication",
            summary = "Add an application",
            description = "Live at once, as a release of one change. " + SCHEMA_NOTE + " A navigation item opens an "
                    + "object, a record page or a path inside the organization, never an arbitrary address. "
                    + "CONFLICT when an object or record page it opens does not exist, or at the limit. Audited.")
    ResponseEntity<ApiResponse<ApplicationView>> createApplication(@Valid @RequestBody CreateApplicationRequest body) {
        ApplicationView created = administration.runAll("metadata.application.create", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.CREATE_APPLICATION, "", null, body),
                        ApplicationView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(APPLICATIONS + "/{applicationApiName}")
    @Operation(
            operationId = "updateApplication",
            summary = "Change an application",
            description = "Live at once, as a release of one change. The API name never changes. " + SCHEMA_NOTE
                    + " CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.")
    ApiResponse<ApplicationView> updateApplication(@PathVariable String applicationApiName,
            @Valid @RequestBody UpdateApplicationRequest body) {
        return ApiResponse.of(administration.runAll("metadata.application.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_APPLICATION, "", applicationApiName, body),
                        ApplicationView.class)));
    }

    @DeleteMapping(APPLICATIONS + "/{applicationApiName}")
    @Operation(
            operationId = "deleteApplication",
            summary = "Remove an application",
            description = "Live at once, as a release of one change. NOT_FOUND for an application the organization "
                    + "does not have. Audited.")
    ResponseEntity<Void> deleteApplication(@PathVariable String applicationApiName) {
        administration.runAll("metadata.application.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_APPLICATION, "", applicationApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    // ---- tabs (Sprint 18) ----

    @GetMapping(TABS)
    @Operation(
            operationId = "listTabDefinitions",
            summary = "The tabs of the organization, as defined",
            description = "For members who may view objects and fields.")
    ApiResponse<List<TabView>> listTabs() {
        return ApiResponse.of(administration.run("metadata.tab.list", Ability.METADATA_VIEW,
                caller -> service.tabs()));
    }

    @GetMapping(TABS + "/{apiName}")
    @Operation(
            operationId = "getTabDefinition",
            summary = "One tab, as defined",
            description = "NOT_FOUND for a tab the organization does not have.")
    ApiResponse<TabView> getTab(@PathVariable String apiName) {
        return ApiResponse.of(administration.run("metadata.tab.get", Ability.METADATA_VIEW,
                caller -> service.tab(apiName)));
    }

    @PostMapping(TABS)
    @Operation(
            operationId = "createTab",
            summary = "Add a tab",
            description = "Live at once, as a release of one change. " + SCHEMA_NOTE + " A tab of kind CUSTOM names the custom page it shows. CONFLICT when that page does not exist or at the limit. Audited.")
    ResponseEntity<ApiResponse<TabView>> createTab(@Valid @RequestBody CreateTabRequest body) {
        TabView created = administration.runAll("metadata.tab.create", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.CREATE_TAB, "", null, body),
                        TabView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(TABS + "/{apiName}")
    @Operation(
            operationId = "updateTab",
            summary = "Change a tab",
            description = "Live at once, as a release of one change. The API name never changes. " + SCHEMA_NOTE
                    + " CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.")
    ApiResponse<TabView> updateTab(@PathVariable String apiName, @Valid @RequestBody UpdateTabRequest body) {
        return ApiResponse.of(administration.runAll("metadata.tab.update", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.UPDATE_TAB, "", apiName, body),
                        TabView.class)));
    }

    @DeleteMapping(TABS + "/{apiName}")
    @Operation(
            operationId = "deleteTab",
            summary = "Remove a tab",
            description = "Live at once, as a release of one change. CONFLICT, naming what still uses it, when "
                    + "something depends on it. NOT_FOUND for a tab the organization does not have. "
                    + "Audited.")
    ResponseEntity<Void> deleteTab(@PathVariable String apiName) {
        administration.runAll("metadata.tab.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_TAB, "", apiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    // ---- custom pages (Sprint 18) ----

    @GetMapping(CUSTOM_PAGES)
    @Operation(
            operationId = "listCustomPageDefinitions",
            summary = "The custom pages of the organization, as defined",
            description = "For members who may view objects and fields.")
    ApiResponse<List<CustomPageView>> listCustomPages() {
        return ApiResponse.of(administration.run("metadata.custompage.list", Ability.METADATA_VIEW,
                caller -> service.customPages()));
    }

    @GetMapping(CUSTOM_PAGES + "/{apiName}")
    @Operation(
            operationId = "getCustomPageDefinition",
            summary = "One custom page, as defined",
            description = "NOT_FOUND for a custom page the organization does not have.")
    ApiResponse<CustomPageView> getCustomPage(@PathVariable String apiName) {
        return ApiResponse.of(administration.run("metadata.custompage.get", Ability.METADATA_VIEW,
                caller -> service.customPage(apiName)));
    }

    @PostMapping(CUSTOM_PAGES)
    @Operation(
            operationId = "createCustomPage",
            summary = "Add a custom page",
            description = "Live at once, as a release of one change. " + SCHEMA_NOTE + " The content names the uploaded script (upload it first, see uploadCustomPageScript) with its hash and size; VALIDATION_ERROR when the script is not in this organization or the hash or size is not its own. The reference for the server is stored and never read or run. CONFLICT at the limit. Audited.")
    ResponseEntity<ApiResponse<CustomPageView>> createCustomPage(@Valid @RequestBody CreateCustomPageRequest body) {
        CustomPageView created = administration.runAll("metadata.custompage.create", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.CREATE_CUSTOM_PAGE, "", null, body),
                        CustomPageView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(CUSTOM_PAGES + "/{apiName}")
    @Operation(
            operationId = "updateCustomPage",
            summary = "Change a custom page",
            description = "Live at once, as a release of one change. The API name never changes. " + SCHEMA_NOTE
                    + " CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.")
    ApiResponse<CustomPageView> updateCustomPage(@PathVariable String apiName, @Valid @RequestBody UpdateCustomPageRequest body) {
        return ApiResponse.of(administration.runAll("metadata.custompage.update", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.UPDATE_CUSTOM_PAGE, "", apiName, body),
                        CustomPageView.class)));
    }

    @DeleteMapping(CUSTOM_PAGES + "/{apiName}")
    @Operation(
            operationId = "deleteCustomPage",
            summary = "Remove a custom page",
            description = "Live at once, as a release of one change. CONFLICT, naming what still uses it, when "
                    + "something depends on it. NOT_FOUND for a custom page the organization does not have. "
                    + "Audited.")
    ResponseEntity<Void> deleteCustomPage(@PathVariable String apiName) {
        administration.runAll("metadata.custompage.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_CUSTOM_PAGE, "", apiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
