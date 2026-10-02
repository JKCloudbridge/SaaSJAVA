package app.platform.licensing.internal;

import app.platform.licensing.Licences;
import app.platform.licensing.PoolView;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The rules of licences (ADR-0032). The pool row is locked before the count is read, so two requests for the last free
 * licence are decided one after the other; the database guards (a trigger on the assignment and on the pool) say the
 * same
 * thing again, so a bug here cannot break the rules. A refusal is a {@code CONFLICT} whose words never reveal more than
 * the numbers an administrator already sees.
 */
@Service
class DefaultLicences implements Licences {

    static final String NO_FREE_LICENCE = "No licence of this type is free.";
    static final String NO_POOL = "The organization has no licences of this type.";

    private final LicensingStore store;
    private final OrganizationWork work;
    private final LicensingProperties properties;

    DefaultLicences(LicensingStore store, OrganizationWork work, LicensingProperties properties) {
        this.store = store;
        this.work = work;
        this.properties = properties;
    }

    @Override
    public List<PoolView> pools() {
        return work.inCurrent(store::pools);
    }

    @Override
    public Map<UUID, String> assigned() {
        return work.inCurrent(store::assigned);
    }

    @Override
    public void assign(UUID membershipId, String licenceType, ActorId actor) {
        UUID typeId = store.licenceTypeId(licenceType)
                .orElseThrow(() -> ApiException.notFound("This licence type does not exist."));
        work.inCurrent(() -> {
            doAssign(membershipId, typeId, licenceType, actor, true);
            return null;
        });
    }

    @Override
    public boolean release(UUID membershipId, ActorId actor) {
        return Boolean.TRUE.equals(work.inCurrent(() -> store.releaseAssignment(membershipId, actor)));
    }

    @Override
    public boolean assignDefault(UUID membershipId, ActorId actor) {
        String type = properties.defaultLicenceType();
        Optional<UUID> typeId = store.licenceTypeId(type);
        if (typeId.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(work.inCurrent(() -> doAssign(membershipId, typeId.get(), type, actor, false)));
    }

    @Override
    public void setPoolQuantity(String licenceType, int quantity, ActorId actor) {
        if (quantity < 0 || quantity > 100_000) {
            throw ApiException.validation("quantity", "Must be between 0 and 100000.");
        }
        UUID typeId = store.licenceTypeId(licenceType)
                .orElseThrow(() -> ApiException.notFound("This licence type does not exist."));
        work.inCurrent(() -> {
            Optional<LicensingStore.PoolRow> pool = store.lockPool(typeId);
            if (pool.isEmpty()) {
                store.insertPool(typeId, quantity, actor);
                return null;
            }
            if (quantity < store.used(typeId)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "A pool cannot be reduced below the licences that are in use. Release some first.");
            }
            try {
                store.updatePool(pool.get().id(), quantity, actor);
            } catch (DataIntegrityViolationException e) {
                // The database guard decided the same way (a race the lock should have excluded); no driver text kept.
                throw new ApiException(ErrorCode.CONFLICT,
                        "A pool cannot be reduced below the licences that are in use. Release some first.");
            }
            return null;
        });
    }

    /**
     * Gives the member a licence of the type. With {@code strict} a refusal is an exception; without it, a false.
     * Runs inside the caller's transaction.
     */
    private boolean doAssign(UUID membershipId, UUID typeId, String type, ActorId actor, boolean strict) {
        Optional<LicensingStore.PoolRow> pool = store.lockPool(typeId);
        if (pool.isEmpty()) {
            return refuse(strict, NO_POOL);
        }
        Optional<LicensingStore.AssignmentRow> current = store.assignmentOf(membershipId);
        if (current.isPresent() && current.get().licenceType().equals(type)) {
            return true;
        }
        if (current.isPresent() && !strict) {
            // An automatic assignment never takes a licence away from someone who already holds another type.
            return false;
        }
        if (store.used(typeId) >= pool.get().quantity()) {
            return refuse(strict, NO_FREE_LICENCE);
        }
        if (!strict) {
            // Everything the database guard checks was checked above, under the pool lock; the caller's transaction
            // (an acceptance, a reactivation) must not be poisoned by a statement that fails, so nothing is caught.
            store.insertAssignment(membershipId, typeId, actor);
            return true;
        }
        try {
            current.ifPresent(held -> store.releaseAssignment(membershipId, actor));
            store.insertAssignment(membershipId, typeId, actor);
        } catch (DataIntegrityViolationException e) {
            // The guard in the database refused (the member is not active, or a race the lock excluded). The
            // transaction rolls back with the exception; no driver text is kept.
            throw new ApiException(ErrorCode.CONFLICT, NO_FREE_LICENCE);
        }
        return true;
    }

    private static boolean refuse(boolean strict, String message) {
        if (strict) {
            throw new ApiException(ErrorCode.CONFLICT, message);
        }
        return false;
    }
}
