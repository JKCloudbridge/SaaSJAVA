package app.platform.metadata.internal;

import app.platform.metadata.CustomPage;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.storage.ObjectStore;
import app.platform.sharedkernel.storage.ObjectStoreException;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Developer convenience (Sprint 18, ADR-0127): gives {@code tenant-a} one working custom page, an application that
 * lists
 * it and the sample object, so the application launcher, the control bar and the sandboxed frame have something real to
 * show on a developer machine. The script is {@code local/objects-overview.js}, an ordinary custom page written against
 * the interface every custom page gets. Only the {@code local} profile runs it, and only when the organization does not
 * have the page yet; a store that is not running only means that it tries again at the next start.
 */
@Component
@Profile("local")
@Order(4)
class LocalCustomPageSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalCustomPageSeeder.class);
    private static final String SCRIPT = "/local/objects-overview.js";
    private static final String PAGE = "ObjectsOverview__c";

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final DefinitionStore definitions;
    private final CustomPageAssetStore assets;
    private final ObjectStore objects;

    LocalCustomPageSeeder(Tenants tenants, TenantContexts contexts, TransactionTemplate transaction,
            DefinitionStore definitions, CustomPageAssetStore assets, ObjectStore objects) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.transaction = transaction;
        this.definitions = definitions;
        this.assets = assets;
        this.objects = objects;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!objects.configured()) {
            LOG.warn("The local object store is not set up; the sample custom page is not made");
            return;
        }
        try {
            objects.ensureBucket();
            tenants.findBySlug(new TenantSlug("tenant-a")).map(Tenant::id).ifPresent(tenant ->
                    contexts.run(TenantContext.of(tenant), () -> transaction.executeWithoutResult(status ->
                            seed(tenant.value()))));
        } catch (ObjectStoreException e) {
            LOG.warn("The local object store did not answer; the sample custom page is not made this time");
        }
    }

    private void seed(UUID tenant) {
        if (definitions.find(DefinitionStore.Table.CUSTOM_PAGE, "", PAGE).isPresent()) {
            return;
        }
        byte[] script = script();
        String sha256 = sha256(script);
        UUID actor = ActorId.SYSTEM.value();
        CustomPageAssetStore.Row asset = assets.findBySha256(sha256).orElseGet(() -> {
            CustomPageAssetStore.Row made = assets.insert(sha256, script.length, actor);
            objects.put(CustomPage.objectKey(tenant, made.id()), new ByteArrayInputStream(script), script.length,
                    "text/javascript");
            return made;
        });
        String frontend = "{\"assetId\":\"" + asset.id() + "\",\"sha256\":\"" + sha256 + "\",\"size\":"
                + script.length + "}";
        definitions.insert(DefinitionStore.Table.CUSTOM_PAGE, "", PAGE, "Objects overview",
                "Sample custom page for the local profile.", "{\"frontend\":" + frontend + "}", actor);
        definitions.insert(DefinitionStore.Table.TAB, "", "ObjectsOverviewTab__c", "Objects overview",
                "Shows the sample custom page.", "{\"kind\":\"CUSTOM\",\"customPage\":\"" + PAGE + "\"}", actor);
        definitions.insert(DefinitionStore.Table.APPLICATION, "", "FieldService__c", "Field Service",
                "Sample application for the local profile.", "{\"iconKey\":\"tool\",\"profiles\":[],\"items\":["
                        + "{\"type\":\"HEADING\",\"label\":\"Overview\"},"
                        + "{\"type\":\"TAB\",\"tab\":\"ObjectsOverviewTab__c\"},"
                        + "{\"type\":\"HEADING\",\"label\":\"Records\"},"
                        + "{\"type\":\"OBJECT\",\"object\":\"Employee__c\"}]}", actor);
        LOG.info("Local organization tenant-a has its sample custom page {}", PAGE);
    }

    private static byte[] script() {
        try (InputStream in = LocalCustomPageSeeder.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("The sample custom page script is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The sample custom page script cannot be read", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
