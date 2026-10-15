package app.platform.application.internal;

import app.platform.metadata.CustomPage;
import app.platform.sharedkernel.storage.ObjectStore;
import app.platform.sharedkernel.storage.ObjectStoreException;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.CustomPageScriptView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hands the script of a custom page to a member's browser (Sprint 18, ADR-0127). The member may have it when an
 * application they see lists a tab that shows the page ({@link MyApplications#openable}); anything else, including a
 * page that does not exist, is "not found" in the same words. The bytes come from the object store, are checked against
 * the hash the page's definition holds (a script that is not what was published is never handed out) and are returned
 * as text. The application never runs, parses or changes them.
 *
 * <p>Reading happens outside any database transaction, so a slow store holds no connection.
 */
@Service
class CustomPageScripts {

    private static final Logger LOG = LoggerFactory.getLogger(CustomPageScripts.class);

    private final MyApplications applications;
    private final ObjectStore objects;
    private final TenantContexts contexts;

    CustomPageScripts(MyApplications applications, ObjectStore objects, TenantContexts contexts) {
        this.applications = applications;
        this.objects = objects;
        this.contexts = contexts;
    }

    /**
     * The script of the custom page.
     *
     * @throws ApiException {@code NOT_FOUND} when the member may not open it or it does not exist,
     *         {@code SERVICE_UNAVAILABLE} when the object store cannot give the script
     */
    CustomPageScriptView script(String customPageApiName) {
        CustomPage page = applications.openable(customPageApiName)
                .orElseThrow(() -> ApiException.notFound("This page does not exist."));
        if (!objects.configured()) {
            throw ApiException.unavailable(60);
        }
        UUID tenant = contexts.current().map(TenantContext::tenantId).map(id -> id.value())
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        byte[] bytes;
        try (ObjectStore.Stored stored = objects.get(page.objectKey(tenant))) {
            if (stored == null) {
                LOG.error("a published custom page script is missing from the object store, asset {}",
                        page.assetId());
                throw ApiException.unavailable(60);
            }
            bytes = stored.bytes().readNBytes((int) page.size() + 1);
        } catch (ObjectStoreException | IOException e) {
            LOG.warn("a custom page script could not be read from the object store, asset {}", page.assetId());
            throw ApiException.unavailable(30);
        }
        if (bytes.length != page.size() || !page.sha256().equals(sha256(bytes))) {
            LOG.error("a custom page script is not what was published, asset {}", page.assetId());
            throw ApiException.unavailable(60);
        }
        return new CustomPageScriptView(page.apiName(), page.label(), page.assetId(), page.sha256(),
                new String(bytes, StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
