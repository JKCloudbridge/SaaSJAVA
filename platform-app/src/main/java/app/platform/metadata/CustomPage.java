package app.platform.metadata;

import java.util.UUID;

/**
 * A custom page of the organization, as published (Sprint 18, ADR-0127): a label and the script that draws it. The
 * script is an uploaded asset, identified by its content; the browser runs it inside a sandbox and it reaches the
 * platform only through the data interface, with the permissions of whoever looks at the page.
 *
 * <p>The definition also holds a reference to code for the server. It is deliberately not part of this type: nothing in
 * the platform reads it, and nothing ever runs because of it (decision D6, ADR-0127).
 *
 * @param apiName its permanent name, ending with {@code __c}
 * @param label the label people read
 * @param description what it is for
 * @param assetId the uploaded script
 * @param sha256 the SHA-256 of the script, in lower case hexadecimal
 * @param size the number of bytes of the script
 * @param version the version, for concurrent changes
 */
public record CustomPage(String apiName, String label, String description, UUID assetId, String sha256, long size,
        long version) {

    /**
     * Where the bytes of the script are in the object store: a key made of the organization and the asset, never of a
     * name a person typed.
     *
     * @param tenantId the organization the page belongs to
     */
    public String objectKey(UUID tenantId) {
        return objectKey(tenantId, assetId);
    }

    /** The object store key of an asset of an organization. */
    public static String objectKey(UUID tenantId, UUID assetId) {
        return "custom-pages/" + tenantId + "/" + assetId;
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "CustomPage[" + apiName + "]";
    }
}
