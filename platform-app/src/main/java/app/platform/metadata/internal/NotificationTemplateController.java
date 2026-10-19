package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CheckNotificationTemplateRequest;
import app.platformapi.CreateNotificationTemplateRequest;
import app.platformapi.NotificationTemplateCheckView;
import app.platformapi.NotificationTemplateView;
import app.platformapi.NotificationTemplatesView;
import app.platformapi.UpdateNotificationTemplateRequest;
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
 * The text of notices and mails (Sprint 25, ADR-0155). Thin: the rules are in {@link NotificationTemplateService}.
 * Answered only on an organization host; reading needs {@code metadata.view}; a change is made live at once, which is a
 * publication of one change, so it needs {@code metadata.manage} and {@code metadata.publish} (ADR-0068); checking
 * needs
 * {@code metadata.view} and either of the other two. Several changes that belong together go through a change set
 * instead, where the same abilities are asked when the set is checked or published.
 */
@RestController
@Tag(name = "Notification text")
class NotificationTemplateController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final List<Ability> EITHER = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String TEMPLATES = ApiPaths.METADATA_NOTIFICATION_TEMPLATES;
    private static final String TEXT_NOTE = "The text is plain: at most 80 characters of title on one line and 500 "
            + "of body, no markup, no link and no address (the platform adds its own link). It may use the "
            + "placeholders of its kind in braces; each stands for a label of the organization and never for a "
            + "value of a record. Audited by kind, never by the text.";

    private final MetadataAdministration administration;
    private final NotificationTemplateService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    NotificationTemplateController(MetadataAdministration administration, NotificationTemplateService service,
            MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(TEMPLATES)
    @Operation(
            operationId = "listNotificationTemplates",
            summary = "The text of every kind of notice an administrator may write",
            description = "For each kind: the text in use (the platform's own until somebody writes one), the "
                    + "platform's own text to start from, the placeholders the kind allows and the version to send "
                    + "back. For members who may view metadata.")
    ApiResponse<NotificationTemplatesView> list() {
        return ApiResponse.of(administration.run("metadata.notificationtemplate.list", Ability.METADATA_VIEW,
                caller -> service.templates()));
    }

    @PostMapping(TEMPLATES)
    @Operation(
            operationId = "createNotificationTemplate",
            summary = "Write the text of a kind of notice",
            description = "Live at once, as a release of one change. " + TEXT_NOTE + " VALIDATION_ERROR names every "
                    + "place that is wrong, also when the text has been written already for the kind.")
    ResponseEntity<ApiResponse<NotificationTemplateView>> create(
            @Valid @RequestBody CreateNotificationTemplateRequest body) {
        NotificationTemplateView created = administration.runAll("metadata.notificationtemplate.create", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.CREATE_NOTIFICATION_TEMPLATE, "",
                        body.kind().strip(), body), NotificationTemplateView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(TEMPLATES + "/{kind}")
    @Operation(
            operationId = "updateNotificationTemplate",
            summary = "Change the text of a kind of notice",
            description = "Live at once, as a release of one change. " + TEXT_NOTE + " CONCURRENT_MODIFICATION when "
                    + "someone changed it since it was read; NOT_FOUND when the platform's own text is in use.")
    ApiResponse<NotificationTemplateView> update(@PathVariable String kind,
            @Valid @RequestBody UpdateNotificationTemplateRequest body) {
        return ApiResponse.of(administration.runAll("metadata.notificationtemplate.update", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.UPDATE_NOTIFICATION_TEMPLATE, "", kind,
                        body), NotificationTemplateView.class)));
    }

    @DeleteMapping(TEMPLATES + "/{kind}")
    @Operation(
            operationId = "resetNotificationTemplate",
            summary = "Go back to the platform's own text",
            description = "Live at once, as a release of one change. Notices already created are not rewritten: "
                    + "their words are built when they are read, so they show the platform's text from now on. The "
                    + "undo of the release writes the text again.")
    ResponseEntity<Void> reset(@PathVariable String kind) {
        administration.runAll("metadata.notificationtemplate.reset", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_NOTIFICATION_TEMPLATE, "", kind, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(TEMPLATES + "/check")
    @Operation(
            operationId = "checkNotificationTemplate",
            summary = "Check a text and see it with sample labels",
            description = "The real rules answer. Nothing is stored, audited or logged, and no part of the text is "
                    + "repeated in the answer. The preview uses sample labels and never a value of a record. Needs "
                    + "metadata.view and metadata.manage or metadata.publish.")
    ApiResponse<NotificationTemplateCheckView> check(@Valid @RequestBody CheckNotificationTemplateRequest body) {
        return ApiResponse.of(administration.runAny("metadata.notificationtemplate.check", Ability.METADATA_VIEW,
                EITHER, caller -> service.check(body)));
    }
}
