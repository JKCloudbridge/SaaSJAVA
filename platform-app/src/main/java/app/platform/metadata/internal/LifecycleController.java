package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ChangeRequest;
import app.platformapi.ChangeSetReportView;
import app.platformapi.ChangeSetView;
import app.platformapi.CreateChangeSetRequest;
import app.platformapi.ReleaseView;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Change sets, publication and history of the organization's metadata (Sprint 11, ADR-0066 to ADR-0068). Thin: the
 * rules are in {@link MetadataLifecycle}. Answered only on an organization host. Drafting a change set needs
 * {@code metadata.manage}; checking or previewing one needs {@code metadata.view} and either {@code metadata.manage} or
 * {@code metadata.publish}; publishing and rolling back need {@code metadata.publish} and {@code metadata.view}. The
 * organization is the host's, never a parameter, and a change set of another organization does not exist.
 */
@RestController
@Tag(name = "Metadata")
class LifecycleController {

    private static final List<Ability> CHECKERS = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final List<Ability> PUBLISHERS = List.of(Ability.METADATA_PUBLISH, Ability.METADATA_VIEW);
    private static final String SETS = ApiPaths.METADATA_CHANGE_SETS;
    private static final String RELEASES = ApiPaths.METADATA_RELEASES;

    private final MetadataAdministration administration;
    private final MetadataLifecycle lifecycle;
    private final MetadataAudit audit;

    LifecycleController(MetadataAdministration administration, MetadataLifecycle lifecycle, MetadataAudit audit) {
        this.administration = administration;
        this.lifecycle = lifecycle;
        this.audit = audit;
    }

    // ---- change sets ----

    @GetMapping(SETS)
    @Operation(
            operationId = "listChangeSets",
            summary = "The change sets of the organization",
            description = "Open drafts first, then published and discarded ones. For members who may view objects "
                    + "and fields. NOT_FOUND on the platform host.")
    ApiResponse<List<ChangeSetView>> list() {
        return ApiResponse.of(administration.run("metadata.changeset.list", Ability.METADATA_VIEW,
                caller -> lifecycle.sets()));
    }

    @PostMapping(SETS)
    @Operation(
            operationId = "createChangeSet",
            summary = "Start a change set",
            description = "A named group of intended changes that is published all together or not at all. A draft "
                    + "is invisible to everything that reads the organization's metadata until it is published. "
                    + "VALIDATION_ERROR for a name in use, CONFLICT at the limit of open change sets. Audited.")
    ResponseEntity<ApiResponse<ChangeSetView>> create(@Valid @RequestBody CreateChangeSetRequest body) {
        ChangeSetView created = administration.run("metadata.changeset.create", Ability.METADATA_MANAGE,
                caller -> lifecycle.createSet(caller, body));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @GetMapping(SETS + "/{id}")
    @Operation(
            operationId = "getChangeSet",
            summary = "One change set with its changes",
            description = "NOT_FOUND for a change set the organization does not have.")
    ApiResponse<ChangeSetView> get(@PathVariable UUID id) {
        return ApiResponse.of(administration.run("metadata.changeset.get", Ability.METADATA_VIEW,
                caller -> lifecycle.set(id)));
    }

    @DeleteMapping(SETS + "/{id}")
    @Operation(
            operationId = "discardChangeSet",
            summary = "Discard an open change set",
            description = "Nothing it holds was ever live. CONFLICT for one that was published or discarded. "
                    + "Audited.")
    ResponseEntity<Void> discard(@PathVariable UUID id) {
        administration.run("metadata.changeset.discard", Ability.METADATA_MANAGE, caller -> {
            lifecycle.discard(caller, id);
            return Boolean.TRUE;
        });
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(SETS + "/{id}/changes")
    @Operation(
            operationId = "addChange",
            summary = "Add a change to an open change set",
            description = "The change carries the request the live endpoint takes. It is not applied: nothing is "
                    + "checked beyond its shape until the set is checked or published. VALIDATION_ERROR for a change "
                    + "that does not say what it needs, CONFLICT for a set that is no longer open or full. Audited.")
    ResponseEntity<ApiResponse<ChangeSetView>> addChange(@PathVariable UUID id,
            @Valid @RequestBody ChangeRequest body) {
        ChangeSetView changed = administration.run("metadata.changeset.change", Ability.METADATA_MANAGE,
                caller -> lifecycle.addChange(caller, id, body));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(changed));
    }

    @DeleteMapping(SETS + "/{id}/changes/{changeId}")
    @Operation(
            operationId = "removeChange",
            summary = "Take a change out of an open change set",
            description = "NOT_FOUND for a change the set does not have. Audited.")
    ApiResponse<ChangeSetView> removeChange(@PathVariable UUID id, @PathVariable UUID changeId) {
        return ApiResponse.of(administration.run("metadata.changeset.change", Ability.METADATA_MANAGE,
                caller -> lifecycle.removeChange(caller, id, changeId)));
    }

    @PostMapping(SETS + "/{id}/validate")
    @Operation(
            operationId = "validateChangeSet",
            summary = "Check a change set without keeping anything",
            description = "Applies the changes by the real rules, checks what depends on what, and rolls everything "
                    + "back. The answer lists every problem (each naming what depends on it) and what would be "
                    + "added, changed or removed. Needs the ability to view and either to manage or to publish.")
    ApiResponse<ChangeSetReportView> validate(@PathVariable UUID id) {
        return ApiResponse.of(check(id, false));
    }

    @PostMapping(SETS + "/{id}/preview")
    @Operation(
            operationId = "previewChangeSet",
            summary = "Show what a change set would make, without keeping anything",
            description = "The same check as validate, and in addition the affected objects as they would be after "
                    + "publishing. Needs the ability to view and either to manage or to publish.")
    ApiResponse<ChangeSetReportView> preview(@PathVariable UUID id) {
        return ApiResponse.of(check(id, true));
    }

    @PostMapping(SETS + "/{id}/publish")
    @Operation(
            operationId = "publishChangeSet",
            summary = "Publish a change set",
            description = "Puts all of it live or none of it, as one release. CONFLICT with every problem (each "
                    + "naming what depends on it) when it cannot be published, or when it is no longer open. "
                    + "Needs the abilities to publish and to view. Audited.")
    ApiResponse<ChangeSetView> publish(@PathVariable UUID id) {
        return ApiResponse.of(administration.runAll("metadata.changeset.publish", PUBLISHERS,
                caller -> lifecycle.publish(caller, id)));
    }

    // ---- releases ----

    @GetMapping(RELEASES)
    @Operation(
            operationId = "listReleases",
            summary = "The history of publications",
            description = "Newest first: each release says what it added, changed or removed, whether it was rolled "
                    + "back, and whether it is the latest. For members who may view objects and fields.")
    ApiResponse<List<ReleaseView>> releases() {
        return ApiResponse.of(administration.run("metadata.release.list", Ability.METADATA_VIEW,
                caller -> lifecycle.releases()));
    }

    @PostMapping(RELEASES + "/latest/rollback-check")
    @Operation(
            operationId = "checkRollback",
            summary = "Check what rolling back the latest release would do",
            description = "Applies the undo by the real rules and rolls everything back. The answer lists every "
                    + "problem, including records or values that would be lost, and what would be undone.")
    ApiResponse<ChangeSetReportView> checkRollback() {
        return ApiResponse.of(administration.rehearse("metadata.release.check", Ability.METADATA_VIEW, CHECKERS,
                ChangeSetReportView.class, lifecycle::rehearseRollback, (caller, report) -> { }));
    }

    @PostMapping(RELEASES + "/latest/rollback")
    @Operation(
            operationId = "rollbackLatestRelease",
            summary = "Roll back the latest release",
            description = "Undoes the latest release as a new release. Only the latest can be rolled back; rolling "
                    + "back a rollback redoes it. CONFLICT with every problem when it cannot be undone. Needs the "
                    + "abilities to publish and to view. Audited.")
    ApiResponse<ReleaseView> rollback() {
        return ApiResponse.of(administration.runAll("metadata.release.rollback", PUBLISHERS,
                lifecycle::rollbackLatest));
    }

    private ChangeSetReportView check(UUID id, boolean preview) {
        return administration.rehearse("metadata.changeset.check", Ability.METADATA_VIEW, CHECKERS,
                ChangeSetReportView.class, caller -> lifecycle.rehearseSet(caller, id, preview),
                (caller, report) -> audit.changeSetChecked(caller.userId(), id, preview ? "preview" : "validate",
                        report.valid(), report.problems().size()));
    }
}
