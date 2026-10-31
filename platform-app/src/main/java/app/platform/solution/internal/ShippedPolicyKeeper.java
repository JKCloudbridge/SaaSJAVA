package app.platform.solution.internal;

import app.platform.security.ShippedAccessPolicies;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the access policies of a solution in step in the organization of the current tenant context (ADR-0181): the
 * first time they are needed after the organization gained the plan feature, and again when a release changes what a
 * policy gives. It runs where the organization's own menu is built, because that is the first thing every member asks
 * for after a plan change; it needs no way across organizations, no start-up sweep and no privileged connection.
 *
 * <p>The cheap question comes first (one read: are the policies as declared?), and only a missing or outdated policy
 * makes a write, in a transaction of its own. A failure to write never reaches the person: the menu is still drawn, the
 * failure is counted and logged by its type alone, and the next request tries again.
 */
@Component
class ShippedPolicyKeeper {

    private static final Logger LOG = LoggerFactory.getLogger(ShippedPolicyKeeper.class);

    private final ShippedAccessPolicies policies;
    private final MeterRegistry meters;

    ShippedPolicyKeeper(ShippedAccessPolicies policies, MeterRegistry meters) {
        this.policies = policies;
        this.meters = meters;
    }

    /** The declarations of the bundle in the shape the security module takes. */
    static List<ShippedAccessPolicies.Declaration> declarations(SolutionBundle bundle) {
        return declarations(bundle.policies(), bundle);
    }

    /** The declarations of the given policies of the bundle. */
    private static List<ShippedAccessPolicies.Declaration> declarations(List<SolutionBundle.Policy> policies,
            SolutionBundle bundle) {
        return policies.stream().map(policy -> new ShippedAccessPolicies.Declaration(
                policy.managedKey(bundle.key()), policy.name(), policy.description(), policy.objects(),
                policy.abilities(), policy.fields())).toList();
    }

    /**
     * Makes the policies of the solution the organization lacks; never throws. A policy is kept while the solution's
     * own feature is on or any feature it is also available with is (FA13A, ADR-0225): an application that shares
     * another's policies needs them although the other application is off.
     */
    void keep(SolutionBundle bundle, Predicate<String> featureOn) {
        boolean own = featureOn.test(bundle.feature());
        List<SolutionBundle.Policy> wanted = bundle.policies().stream()
                .filter(policy -> own || policy.alsoAvailableWith().stream().anyMatch(featureOn)).toList();
        if (wanted.isEmpty()) {
            return;
        }
        List<ShippedAccessPolicies.Declaration> declared = declarations(wanted, bundle);
        try {
            if (policies.upToDate(SolutionBundle.OWNER, declared)) {
                return;
            }
            ShippedAccessPolicies.Result result = policies.ensure(SolutionBundle.OWNER, declared);
            meters.counter("platform.solution.policies", "result", "ensured").increment(result.made()
                    + result.updated());
        } catch (RuntimeException e) {
            meters.counter("platform.solution.policies", "result", "failed").increment();
            LOG.warn("Making the shipped access policies of solution {} failed ({})", bundle.key(),
                    e.getClass().getSimpleName());
        }
    }
}
