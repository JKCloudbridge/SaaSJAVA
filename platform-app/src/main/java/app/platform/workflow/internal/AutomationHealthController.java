package app.platform.workflow.internal;

import app.platform.identity.OrganizationAdministration;
import app.platform.security.Ability;
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
 * The health of automation in the organization (Sprint 25, ADR-0163): the failure view for an administrator. What waits
 * to run, what retries, what was set aside, which events were given up on, which approval has nobody to decide it or is
 * overdue, which mails are stuck. Counts and the place to look (the lists behind them need their own abilities), never
 * a value of a record. It collects every module's part through the shared-kernel contract, so it depends on none of
 * them. Needs {@code automation.view}; answered only on an organization host.
 */
@RestController
@Tag(name = "Automation health")
class AutomationHealthController {

    private final OrganizationAdministration administration;
    private final List<AutomationHealthSource> sources;
    private final Clock clock;

    AutomationHealthController(OrganizationAdministration administration, List<AutomationHealthSource> sources,
            Clock clock) {
        this.administration = administration;
        this.sources = sources;
        this.clock = clock;
    }

    @GetMapping(ApiPaths.AUTOMATION_HEALTH)
    @Operation(
            operationId = "getAutomationHealth",
            summary = "The health of automation in the organization",
            description = "Counts of what waits, what retries, what was set aside, which events were given up on, "
                    + "which approvals have nobody to decide them or are overdue, and which mails are stuck, each "
                    + "with the place to look. Counts only: never a value of a record. Needs automation.view. "
                    + "Organization hosts only.")
    ApiResponse<AutomationHealthView> health() {
        return ApiResponse.of(administration.asAdministrator("automation.health", Ability.AUTOMATION_VIEW, caller -> {
            List<AutomationHealthSectionView> sections = sources.stream()
                    .flatMap(source -> source.organization().stream())
                    .map(AutomationHealthController::section).toList();
            return new AutomationHealthView(clock.instant(), sections);
        }));
    }

    private static AutomationHealthSectionView section(HealthSection section) {
        return new AutomationHealthSectionView(section.key(), section.title(), section.items().stream()
                .map(item -> new AutomationHealthItemView(item.key(), item.label(), item.count(),
                        item.needsAttention(), item.href())).toList());
    }
}
