package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * The question behind every endpoint of this module: "does the caller hold the ability this action needs?", and what to
 * do when the answer is yes but the target is something the platform protects (ADR-0059) or the publication cannot go
 * ahead (ADR-0065). The ability is checked inside the transaction of the work by {@link OrganizationAdministration};
 * a refusal for a protected definition or for an invalid publication is recorded after that transaction ended, with
 * its own reason, and the caller is told in words.
 */
@Component
class MetadataAdministration {

    static final String PROTECTED_MESSAGE =
            "This is defined by the platform and cannot be changed. You can add your own fields to a standard "
                    + "object where the platform allows it.";

    private final OrganizationAdministration administration;
    private final MetadataAudit audit;
    private final Permissions permissions;

    MetadataAdministration(OrganizationAdministration administration, MetadataAudit audit,
            Permissions permissions) {
        this.administration = administration;
        this.audit = audit;
        this.permissions = permissions;
    }

    /**
     * Runs the work in one transaction after checking the ability inside it.
     *
     * @throws ApiException {@code NOT_FOUND} on the platform host, {@code FORBIDDEN} without the ability or for a
     *         protected definition, {@code CONFLICT} when a publication has problems
     */
    <T> T run(String action, Ability ability, Function<OrganizationAdministration.Caller, T> work) {
        try {
            return administration.asAdministrator(action, ability, work);
        } catch (ProtectedDefinition refused) {
            audit.protectedChangeRefused(refused.actor(), refused.action(), refused.object());
            throw new ApiException(ErrorCode.FORBIDDEN, PROTECTED_MESSAGE);
        } catch (Refused refused) {
            audit.publishRefused(refused.actor(), refused.kind(), refused.changeSet(), refused.problems().size());
            throw refusal(refused);
        }
    }

    /** Like {@link #run}, for work that needs every one of the abilities. */
    <T> T runAll(String action, List<Ability> abilities, Function<OrganizationAdministration.Caller, T> work) {
        return run(action, abilities.get(0), caller -> {
            for (Ability ability : abilities.subList(1, abilities.size())) {
                requireAbility(caller, ability);
            }
            return work.apply(caller);
        });
    }

    /** Like {@link #run}, for work that needs the first ability and at least one of the others. */
    <T> T runAny(String action, Ability base, List<Ability> any,
            Function<OrganizationAdministration.Caller, T> work) {
        return run(action, base, caller -> {
            if (any.stream().noneMatch(ability -> permissions.has(caller.membershipId(), ability))) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            return work.apply(caller);
        });
    }

    /**
     * Runs the work in a transaction that is always rolled back, and returns what the work found: the way to ask "what
     * would happen?" with the real rules and leave no trace (ADR-0066). The ability is checked as for {@link #runAny}.
     * What a check leaves in the audit trail is written by {@code after}, once the transaction has ended.
     */
    <T> T rehearse(String action, Ability base, List<Ability> any, Class<T> type,
            Function<OrganizationAdministration.Caller, T> work,
            BiConsumer<OrganizationAdministration.Caller, T> after) {
        OrganizationAdministration.Caller[] who = new OrganizationAdministration.Caller[1];
        try {
            runAny(action, base, any, caller -> {
                who[0] = caller;
                throw new Rehearsed(work.apply(caller));
            });
        } catch (Rehearsed done) {
            T result = type.cast(done.result());
            after.accept(who[0], result);
            return result;
        }
        throw new IllegalStateException("A rehearsal ended without being rolled back");
    }

    private void requireAbility(OrganizationAdministration.Caller caller, Ability ability) {
        if (!permissions.has(caller.membershipId(), ability)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private static ApiException refusal(Refused refused) {
        List<MetadataLifecycle.Problem> problems = refused.problems();
        String first = problems.get(0).message();
        String message = (refused.kind().equals("ROLLBACK") ? "The latest release cannot be rolled back: "
                : "The change set cannot be published: ") + first
                + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more problems)" : "");
        return new ApiException(ErrorCode.CONFLICT, message,
                Map.of("problems", problems.stream().map(MetadataAdministration::describe).toList()));
    }

    private static String describe(MetadataLifecycle.Problem problem) {
        String prefix = problem.position() == null ? "" : "Change " + problem.position() + ": ";
        String fields = problem.fields().isEmpty() ? "" : " (" + String.join("; ", problem.fields()) + ")";
        return prefix + problem.message() + fields;
    }
}
