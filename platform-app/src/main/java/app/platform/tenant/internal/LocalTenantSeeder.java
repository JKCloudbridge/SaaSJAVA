package app.platform.tenant.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Developer convenience: makes sure two open organizations exist on a developer machine, so that
 * {@code tenant-a.localhost} and {@code tenant-b.localhost} answer. Only the {@code local} profile runs it; no other
 * environment gets tenants this way. Tenants for real are created by sign-up (Sprint 4) and platform administration
 * (Sprint 6).
 */
@Component
@Profile("local")
class LocalTenantSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTenantSeeder.class);
    private static final Map<String, String> TENANTS = Map.of("tenant-a", "Tenant A", "tenant-b", "Tenant B");

    private final Tenants tenants;

    LocalTenantSeeder(Tenants tenants) {
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        TENANTS.forEach((slugText, name) -> {
            TenantSlug slug = new TenantSlug(slugText);
            Tenant tenant = tenants.findBySlug(slug)
                    .orElseGet(() -> tenants.provision(slug, name, ActorId.SYSTEM));
            if (tenant.status() == TenantStatus.PROVISIONING) {
                tenants.activate(tenant.id(), ActorId.SYSTEM);
                LOG.info("Local organization {} is open", slug);
            }
        });
    }
}
