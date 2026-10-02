package app.platform.identity.internal;

import app.platform.identity.PlatformPerson;
import app.platform.identity.PlatformRole;
import app.platform.identity.PlatformRoles;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Platform roles (ADR-0030). A role is granted to an existing, active account, by an existing platform administrator,
 * and every grant, revoke and refusal is audited. Nothing is cached: the roles are read on every call.
 *
 * <p>Granting by address tells a platform administrator whether an address has an account (the answer for an unknown
 * address differs). That is a stated trade-off: the people who may do it are the platform's most trusted operators, and
 * the alternative (a role for an account that does not exist) would be worse.
 */
@Service
class DefaultPlatformRoles implements PlatformRoles {

    static final String LAST_ADMINISTRATOR = "The last platform administrator cannot be removed. Grant the role to "
            + "another person first.";

    private final PlatformRoleRepository roles;
    private final UserRepository users;
    private final AuthAudit audit;
    private final TransactionTemplate transaction;

    DefaultPlatformRoles(PlatformRoleRepository roles, UserRepository users, AuthAudit audit,
            TransactionTemplate transaction) {
        this.roles = roles;
        this.users = users;
        this.audit = audit;
        this.transaction = transaction;
    }

    @Override
    public Set<PlatformRole> of(UUID userId) {
        return roles.rolesOf(userId);
    }

    @Override
    public void require(UUID userId, PlatformRole... anyOf) {
        Set<PlatformRole> held = roles.rolesOf(userId);
        if (Arrays.stream(anyOf).noneMatch(held::contains)) {
            audit.platformActionRefused(userId, Arrays.stream(anyOf).map(Enum::name).toList());
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    @Override
    public List<PlatformPerson> people() {
        return roles.list();
    }

    @Override
    public PlatformPerson grant(UUID actor, String emailText, PlatformRole role) {
        String email = Emails.normalize(emailText)
                .orElseThrow(() -> ApiException.validation("email", "Is not a valid address."));
        User user = users.findByEmail(email).filter(found -> found.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> ApiException.validation("email", "No active account has this address."));
        try {
            return transaction.execute(status -> {
                UUID id = roles.insert(user.id(), role, new ActorId(actor));
                audit.platformRoleGranted(actor, user.id(), role.name());
                return roles.find(id).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "This person already holds this role.");
        }
    }

    @Override
    public void revoke(UUID actor, UUID assignmentId) {
        PlatformPerson person = roles.find(assignmentId)
                .orElseThrow(() -> ApiException.notFound("This role assignment does not exist."));
        try {
            transaction.executeWithoutResult(status -> {
                if (!roles.remove(assignmentId, new ActorId(actor))) {
                    throw ApiException.notFound("This role assignment does not exist.");
                }
                audit.platformRoleRevoked(actor, person.userId(), person.role().name());
            });
        } catch (DataIntegrityViolationException e) {
            // The database refuses to remove the last platform administrator, also when two requests race.
            audit.platformActionRefusedWith(actor, "platform.role.revoke", "last_platform_administrator");
            throw new ApiException(ErrorCode.CONFLICT, LAST_ADMINISTRATOR);
        }
    }

    /** Whether the person holds any platform role (used to apply the stricter protections to such accounts). */
    boolean holdsAny(UUID userId) {
        return !roles.rolesOf(userId).isEmpty();
    }
}
