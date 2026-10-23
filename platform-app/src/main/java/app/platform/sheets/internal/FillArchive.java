package app.platform.sheets.internal;

import app.platform.sharedkernel.storage.ObjectStore;
import app.platform.sharedkernel.storage.ObjectStoreException;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moving the cells of an old fill-in to the object store and reading them back (FF2, ADR-0193).
 *
 * <p><strong>The move</strong> is done in the caller's transaction and organization, for one fill-in, and is safe
 * to run
 * from any number of instances and to repeat after a crash:
 * <ol>
 *   <li>it takes the lock every save and correction of the fill-in takes, so a correction and the move are decided one
 *   after the other, and a second instance waits and then finds the work done;</li>
 *   <li>it reads the cells, writes them again in the canonical form and refuses to go on unless their hash is the one
 *   the row holds (what is archived is exactly what the row vouched for);</li>
 *   <li>it stores the compressed document under a key made of the organization and the fill-in alone, reads it back and
 *   checks the hash once more, <em>before</em> the row stops holding the cells;</li>
 *   <li>only then does the row get the pointer and the cells become the empty object, in the same statement the
 *   database guard allows ({@code V071}).</li>
 * </ol>
 * A crash before the last step leaves at most a stored document no row points to; the next pass stores the same bytes
 * under the same key. A crash after it is a finished move. Nothing is ever deleted: the document stays for as long as
 * the fill-in does.
 *
 * <p><strong>The read</strong> opens the document, unpacks it with a bound, and checks the hash of the result against
 * the row's before handing it out, so a document that was damaged or swapped is never shown as the fill-in.
 *
 * <p>The cells are the organization's text: nothing of them, and no key, is logged or audited. The audit carries the
 * identifiers and the sizes.
 */
@Component
class FillArchive {

    private static final Logger LOG = LoggerFactory.getLogger(FillArchive.class);
    private static final String CONTENT_TYPE = "application/gzip";
    // The database holds at most 5,000,000 bytes of cells (V069); a document that unpacks beyond that is not ours.
    private static final int MOST_UNPACKED = 5_000_001;

    private final SheetFillStore store;
    private final FillValidator validator;
    private final ObjectStore objects;
    private final FillChanges changes;
    private final TenantContexts contexts;

    FillArchive(SheetFillStore store, FillValidator validator, ObjectStore objects, FillChanges changes,
            TenantContexts contexts) {
        this.store = store;
        this.validator = validator;
        this.objects = objects;
        this.changes = changes;
        this.contexts = contexts;
    }

    /** Whether the store is set up at all; a deployment without one archives nothing and says so by doing nothing. */
    boolean available() {
        return objects.configured();
    }

    /**
     * Moves one fill-in's cells to the object store.
     *
     * @return whether this call moved them; false when the fill-in is not approved, was moved already (by this or
     *         another instance) or its cells no longer match the hash the row holds
     * @throws ObjectStoreException when the store fails; the caller's transaction then rolls back, nothing moved
     */
    boolean archive(String fillObject, UUID fillId) {
        store.lock(fillObject, fillId);
        Optional<SheetFillStore.WithCells> found = store.findCells(fillObject, fillId);
        if (found.isEmpty()) {
            return false;
        }
        SheetFillStore.Row row = found.get().row();
        if (row.archived() || !"APPROVED".equals(row.status())) {
            return false;
        }
        byte[] plain = FillValidator.write(validator.read(found.get().cells())).getBytes(StandardCharsets.UTF_8);
        if (!SheetGridWriter.hex(SheetGridWriter.sha256(plain)).equals(row.cellsHash())) {
            // Never archive what the row does not vouch for. Reported by counts and the identifier only.
            changes.archiveProblem("hash");
            LOG.error("The cells of fill-in {} do not match their hash; it is not archived", fillId);
            return false;
        }
        byte[] packed = gzip(plain);
        String key = key(fillId);
        objects.put(key, new ByteArrayInputStream(packed), packed.length, CONTENT_TYPE);
        // Read back before the row lets go of the cells: the only moment the row can still be trusted to be whole.
        if (!row.cellsHash().equals(hashOfStored(key))) {
            changes.archiveProblem("verify");
            LOG.error("The archive document of fill-in {} did not read back whole; it is not archived", fillId);
            return false;
        }
        if (store.archive(fillObject, fillId, key, packed.length).isEmpty()) {
            return false;
        }
        changes.archived(fillObject, fillId, plain.length, packed.length);
        return true;
    }

    /**
     * The canonical cells text of an archived fill-in, verified against the hash of its row.
     *
     * @throws ApiException {@code SERVICE_UNAVAILABLE} when the store fails or the document is missing or damaged; the
     *         words never name a key
     */
    String read(SheetFillStore.Row row) {
        if (!row.archived()) {
            throw new IllegalArgumentException("The fill-in is not archived");
        }
        byte[] plain = unpackedStored(row.archiveKey());
        if (plain == null) {
            changes.archiveProblem("missing");
            LOG.error("The archive document of fill-in {} is missing", row.fillId());
            throw ApiException.unavailable(60);
        }
        if (!SheetGridWriter.hex(SheetGridWriter.sha256(plain)).equals(row.cellsHash())) {
            changes.archiveProblem("mismatch");
            LOG.error("The archive document of fill-in {} does not match its hash", row.fillId());
            throw ApiException.unavailable(60);
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ---- the pieces ----

    private String key(UUID fillId) {
        return "fill-archives/" + contexts.require().tenantId().value() + "/" + fillId;
    }

    private String hashOfStored(String key) {
        byte[] plain = unpackedStored(key);
        return plain == null ? "" : SheetGridWriter.hex(SheetGridWriter.sha256(plain));
    }

    /** The unpacked bytes under the key, or null when there are none or they are not a document of ours. */
    private byte[] unpackedStored(String key) {
        try (ObjectStore.Stored stored = key == null ? null : objects.get(key)) {
            if (stored == null) {
                return null;
            }
            try (GZIPInputStream in = new GZIPInputStream(stored.bytes())) {
                byte[] plain = in.readNBytes(MOST_UNPACKED);
                return plain.length >= MOST_UNPACKED ? null : plain;
            }
        } catch (ObjectStoreException e) {
            throw ApiException.unavailable(30);
        } catch (IOException e) {
            // Not a gzip document, or cut short: treated as damaged by the caller.
            return null;
        }
    }

    private static byte[] gzip(byte[] plain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length / 4 + 64);
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(plain);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
