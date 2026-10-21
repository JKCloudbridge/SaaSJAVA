package app.platform.solution.internal;

import app.platform.licensing.EntitlementView;
import app.platform.licensing.Entitlements;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Developer convenience: switches the plan feature of every shipped solution on for the local organization {@code
 * tenant-a}, so that its application can be seen on a developer machine without first using the console. {@code tenant-b}
 * is left without, which is how the difference between an organization with the feature and one without is looked at.
 * Only the {@code local} profile runs it, and only for a feature that has no switch for the organization yet, so a
 * developer who switched it off in the console keeps it off.
 */
@Component
@Profile("local")
@Order(5)
class LocalSolutionSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSolutionSeeder.class);

    private final Solutions solutions;
    private final Tenants tenants;
    private final Entitlements entitlements;

    LocalSolutionSeeder(Solutions solutions, Tenants tenants, Entitlements entitlements) {
        this.solutions = solutions;
        this.tenants = tenants;
        this.entitlements = entitlements;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenants.findBySlug(new TenantSlug("tenant-a")).map(Tenant::id).ifPresent(tenant -> {
            for (SolutionBundle bundle : solutions.bundles()) {
                boolean switched = entitlements.of(tenant).stream()
                        .filter(view -> view.key().equals(bundle.feature())).map(EntitlementView::override)
                        .anyMatch(override -> override != null);
                if (!switched) {
                    entitlements.override(tenant, bundle.feature(), true, ActorId.SYSTEM);
                    LOG.info("Local organization tenant-a has the feature of solution {}", bundle.key());
                }
            }
        });
    }
}
