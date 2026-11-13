package app.platform.solution.internal;

import app.platform.licensing.Entitlements;
import app.platform.sharedkernel.solution.ObjectRoleDeclarations;
import app.platform.sharedkernel.solution.ObjectRoles;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The parts the shipped solutions declare for their applications (FA13B, ADR-0226), given to the module that owns the
 * functions of such an application through the shared-kernel contract. The list is read once from the solutions, which
 * are fixed while the application runs; whether an organization has an application is asked at the moment of the call
 * from its plan feature and never stored, as the application itself is (ADR-0175).
 */
@Component
final class ShippedObjectRoles implements ObjectRoleDeclarations {

    private final List<ObjectRoles> declared;
    private final Entitlements entitlements;

    ShippedObjectRoles(Solutions solutions, Entitlements entitlements) {
        this.entitlements = entitlements;
        List<ObjectRoles> found = new ArrayList<>();
        for (SolutionBundle bundle : solutions.bundles()) {
            if (!bundle.roles().isEmpty()) {
                bundle.applications().forEach(application -> found.add(
                        new ObjectRoles(application.apiName(), bundle.feature(), bundle.roles(),
                        bundle.options())));
            }
        }
        this.declared = List.copyOf(found);
    }

    @Override
    public List<ObjectRoles> declared() {
        return declared;
    }

    @Override
    public Optional<ObjectRoles> available(String application) {
        if (application == null) {
            return Optional.empty();
        }
        return declared.stream().filter(roles -> roles.application().equals(application))
                .filter(roles -> entitlements.enabled(roles.feature())).findFirst();
    }
}
