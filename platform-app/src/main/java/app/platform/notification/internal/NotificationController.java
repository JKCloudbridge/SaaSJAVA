package app.platform.notification.internal;

import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.NotificationPreferencesView;
import app.platformapi.NotificationSummaryView;
import app.platformapi.NotificationView;
import app.platformapi.UpdateNotificationPreferencesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in member's notices and their choices about them (Sprint 25, ADR-0154, ADR-0156). Thin: the rules are in
 * {@link NotificationService} and {@link NotificationPreferences}. These are the member's own: no ability is asked,
 * because nothing here reaches anybody else's notices, and every endpoint names the member from the token and the host.
 * Answered only on an organization host.
 */
@RestController
@Tag(name = "Notifications")
class NotificationController {

    private static final String NOTIFICATIONS = ApiPaths.NOTIFICATIONS;
    private static final String PREFERENCES = ApiPaths.NOTIFICATION_PREFERENCES;

    private final NotificationService service;
    private final NotificationPreferences preferences;

    NotificationController(NotificationService service, NotificationPreferences preferences) {
        this.service = service;
        this.preferences = preferences;
    }

    @GetMapping(NOTIFICATIONS)
    @Operation(
            operationId = "listNotifications",
            summary = "The caller's notices",
            description = "Newest first, cursor paging. Only the caller's own notices are ever returned. The words "
                    + "are built now from the template of the kind, so a change of text reaches old notices; they "
                    + "hold labels and never a value of a record. The href leads to the inbox or to a record; the "
                    + "page it opens decides what the caller may see. Narrow to the unread ones with unread=true.")
    ApiPageResponse<NotificationView> list(@RequestParam(name = "unread", required = false,
            defaultValue = "false") boolean unread, @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(service.member(), unread, limit, cursor);
    }

    @GetMapping(NOTIFICATIONS + "/summary")
    @Operation(
            operationId = "getNotificationSummary",
            summary = "How many notices the caller has not read",
            description = "For the bell in the header: one count, cheap to ask on focus.")
    ApiResponse<NotificationSummaryView> summary() {
        return ApiResponse.of(service.summary(service.member()));
    }

    @PostMapping(NOTIFICATIONS + "/{id}/read")
    @Operation(
            operationId = "markNotificationRead",
            summary = "Mark one of the caller's notices read",
            description = "NOT_FOUND for a notice that is not the caller's, exactly like one that does not exist. "
                    + "Marking a read notice again changes nothing.")
    ResponseEntity<Void> markRead(@PathVariable UUID id) {
        service.markRead(service.member(), id);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(NOTIFICATIONS + "/read-all")
    @Operation(
            operationId = "markAllNotificationsRead",
            summary = "Mark all of the caller's notices read",
            description = "Only the caller's own.")
    ResponseEntity<Void> markAllRead() {
        service.markAllRead(service.member());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping(PREFERENCES)
    @Operation(
            operationId = "getNotificationPreferences",
            summary = "The caller's choices of which notices and mails they want",
            description = "One entry per category and channel, with the words to show, whether it is on, and whether "
                    + "the platform fixes it on. A switch the caller never touched is on.")
    ApiResponse<NotificationPreferencesView> getPreferences() {
        return ApiResponse.of(preferences.view(service.member()));
    }

    @PutMapping(PREFERENCES)
    @Operation(
            operationId = "updateNotificationPreferences",
            summary = "Change some of the caller's choices",
            description = "Only the switches named change. A switch the platform fixes on (the account category) is "
                    + "refused with VALIDATION_ERROR naming the place. A switch that is off creates no in-app "
                    + "notice from now on and sends no mail of that category; nothing already made is removed.")
    ApiResponse<NotificationPreferencesView> updatePreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest body) {
        return ApiResponse.of(preferences.update(service.member(), body));
    }
}
