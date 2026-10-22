package app.platform.data.internal;

import app.platform.security.Grantee;
import app.platform.security.GranteeRemovals;
import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Ends the explicit shares addressed to a role or a public group that was removed (ADR-0185). The security module tells
 * it, in the transaction that removes the role or group. A share is deleted, not marked (the audit trail keeps the
 * history, ADR-0117); only the count is kept, in the audit record of the removal.
 */
@Component
class DataGranteeRemovals implements GranteeRemovals {

    private final ShareStore shares;

    DataGranteeRemovals(ShareStore shares) {
        this.shares = shares;
    }

    @Override
    public int removed(Grantee.Kind kind, UUID id, ActorId actor) {
        if (kind == null || id == null) {
            return 0;
        }
        List<String> types = kind.isRole()
                ? List.of(Grantee.Kind.ROLE.name(), Grantee.Kind.ROLE_AND_SUBORDINATES.name())
                : List.of(kind.name());
        return shares.deleteGrantee(id, types);
    }
}
