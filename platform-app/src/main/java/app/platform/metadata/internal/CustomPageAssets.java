package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.CustomPage;
import app.platform.metadata.internal.DefinitionSchemas.Issue;
import app.platform.sharedkernel.storage.ObjectStore;
import app.platform.sharedkernel.storage.ObjectStoreException;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.CustomPageAssetView;
import app.platformapi.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The scripts of custom pages (Sprint 18, ADR-0127): taking an upload, and checking that a custom page names a script
 * that exists. A script is text in UTF-8 of at most {@code platform.metadata.limits.max-custom-page-bytes} bytes. It is
 * identified by its content (SHA-256), so the same script uploaded twice is one asset, and an asset never changes.
 * The platform stores the text and never runs, parses or rewrites it: it is run only in a member's browser, inside a
 * sandbox (ADR-0127).
 *
 * <p>The bytes go to the object store under a key made of the organization and the asset
 * ({@link CustomPage#objectKey(UUID, UUID)}), never of a name a person typed. The row is written first, in the
 * transaction of the upload; if the store fails the transaction is rolled back and nothing is left. If the commit fails
 * after the store has the bytes, an object nobody names is left; its key is never reused (a new upload gets a new
 * identifier), so it is harmless, and a later clean-up may remove such objects.
 */
@Service
class CustomPageAssets {

    private final CustomPageAssetStore store;
    private final ObjectStore objects;
    private final TenantContexts contexts;
    private final MetadataProperties properties;
    private final MetadataAudit audit;

    CustomPageAssets(CustomPageAssetStore store, ObjectStore objects, TenantContexts contexts,
            MetadataProperties properties, MetadataAudit audit) {
        this.store = store;
        this.objects = objects;
        this.contexts = contexts;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * Stores a script.
     *
     * @param contentLength the announced length of the body, or a negative number when it was not announced
     * @param body the bytes
     * @throws ApiException {@code VALIDATION_ERROR} for an empty script or text that is not UTF-8,
     *         {@code PAYLOAD_TOO_LARGE} above the limit, {@code CONFLICT} at the most scripts an organization may
     *         keep, {@code SERVICE_UNAVAILABLE} when the object store fails
     */
    CustomPageAssetView upload(Caller caller, long contentLength, InputStream body) {
        int most = properties.limits().maxCustomPageBytes();
        if (contentLength < 0) {
            throw ApiException.validation("body", "Announce the length of the script (Content-Length); chunked "
                    + "bodies are not accepted.");
        }
        if (contentLength > most) {
            throw tooLarge(most);
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes((int) contentLength + 1);
        } catch (IOException e) {
            throw ApiException.validation("body", "The script could not be read.");
        }
        if (bytes.length > contentLength || bytes.length > most) {
            throw tooLarge(most);
        }
        if (bytes.length == 0) {
            throw ApiException.validation("body", "The script is empty.");
        }
        requireText(bytes);
        String sha256 = sha256(bytes);
        Optional<CustomPageAssetStore.Row> existing = store.findBySha256(sha256);
        if (existing.isPresent()) {
            CustomPageAssetStore.Row row = existing.get();
            return new CustomPageAssetView(row.id(), row.sha256(), row.size(), false);
        }
        if (store.count() >= properties.limits().maxCustomPageAssets()) {
            throw new ApiException(ErrorCode.CONFLICT, "The organization has reached the most scripts it may keep ("
                    + properties.limits().maxCustomPageAssets() + ").");
        }
        if (!objects.configured()) {
            throw ApiException.unavailable(60);
        }
        UUID tenant = contexts.current().map(TenantContext::tenantId).map(id -> id.value())
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        CustomPageAssetStore.Row row = store.insert(sha256, bytes.length, caller.userId());
        try {
            objects.put(CustomPage.objectKey(tenant, row.id()), new ByteArrayInputStream(bytes), bytes.length,
                    "text/javascript");
        } catch (ObjectStoreException e) {
            throw ApiException.unavailable(30);
        }
        audit.customPageAssetUploaded(caller.userId(), row.id(), bytes.length);
        return new CustomPageAssetView(row.id(), row.sha256(), row.size(), true);
    }

    /**
     * The places where a custom page's definition names a script that is not there or is not what it says: the asset
     * must exist in this organization and the hash and size must be its own. The definition has already passed its
     * schema.
     */
    List<Issue> problems(JsonNode definition) {
        JsonNode frontend = definition.path("frontend");
        UUID assetId;
        try {
            assetId = UUID.fromString(frontend.path("assetId").asString());
        } catch (IllegalArgumentException e) {
            return List.of(new Issue("definition.frontend.assetId", "Must be the identifier of an uploaded script."));
        }
        Optional<CustomPageAssetStore.Row> asset = store.find(assetId);
        if (asset.isEmpty()) {
            return List.of(new Issue("definition.frontend.assetId",
                    "This script has not been uploaded to this organization. Upload it first."));
        }
        List<Issue> issues = new java.util.ArrayList<>();
        if (!asset.get().sha256().equals(frontend.path("sha256").asString())) {
            issues.add(new Issue("definition.frontend.sha256", "Is not the hash of the uploaded script."));
        }
        if (asset.get().size() != frontend.path("size").asLong()) {
            issues.add(new Issue("definition.frontend.size", "Is not the size of the uploaded script."));
        }
        return issues;
    }

    private static void requireText(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\u0000') >= 0) {
                throw ApiException.validation("body", "The script must be text; it holds a null character.");
            }
        } catch (CharacterCodingException e) {
            throw ApiException.validation("body", "The script must be text in UTF-8.");
        }
    }

    private static ApiException tooLarge(int most) {
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The script is larger than the most this "
                + "organization may upload (" + most + " bytes).");
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
