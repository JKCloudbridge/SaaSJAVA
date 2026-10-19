package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;
import static app.platform.identity.PlatformRole.PLATFORM_SUPPORT;

import app.platform.sharedkernel.health.AutomationHealthSource;
import app.platform.sharedkernel.health.HealthSection;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.AutomationHealthItemView;
import app.platformapi.AutomationHealthSectionView;
import app.platformapi.AutomationHealthView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The health of automation across organizations, for the platform console (Sprint 25, ADR-0163): how many executions
 * wait or are set aside, how old the longest wait is, how many steps are overdue, how many mails are stuck. Counts
 * only: no organization is named, no person and no value, so a platform person learns that something needs attention
 * and never what a client's data holds. Platform host only, for platform administrators and support.
 */
@RestController
@Tag(name = "Platform console")
class PlatformAutomationHealthController {

    private final List<AutomationHealthSource> sources;
    private final Clock clock;

    PlatformAutomationHealthController(List<AutomationHealthSource> sources, Clock clock) {
        this.sources = sources;
        this.clock = clock;
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_SUPPORT})
    @GetMapping(ApiPaths.PLATFORM_AUTOMATION_HEALTH)
    @Operation(
            operationId = "getPlatformAutomationHealth",
            summary = "The health of automation across organizations (platform console)",
            description = "Counts of what waits, what is set aside, what is overdue and what is stuck, across all "
                    + "organizations, without any name or value. For platform administrators and support. Platform "
                    + "host only.")
    ApiResponse<AutomationHealthView> health() {
        List<AutomationHealthSectionView> sections = sources.stream().flatMap(source -> source.platform().stream())
                .map(PlatformAutomationHealthController::section).toList();
        return ApiResponse.of(new AutomationHealthView(clock.instant(), sections));
    }

    private static AutomationHealthSectionView section(HealthSection section) {
        return new AutomationHealthSectionView(section.key(), section.title(), section.items().stream()
                .map(item -> new AutomationHealthItemView(item.key(), item.label(), item.count(),
                        item.needsAttention(), item.href())).toList());
    }
}
