package app.platform.solution.internal;

import app.platform.licensing.Entitlements;
import app.platform.sharedkernel.solution.ShippedApplicationView;
import app.platform.sharedkernel.solution.ShippedApplications;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * The shipped applications of the organization of the current tenant context (ADR-0175, ADR-0181). The application is
 * <em>computed</em>: a solution is listed only while {@link Entitlements#enabled} says the organization has its plan
 * feature, asked at the moment of the call and never stored, so taking the feature away hides the application on the
 * next request and nothing needs cleaning. No row of the organization is written for the application, its entries
 * or its pages. Which entries the member may open is the menu's to judge, with the rule it uses for every
 * application.
 *
 * <p>On the way it makes sure the solution's access policies exist ({@link ShippedPolicyKeeper}).
 */
@Service
class ShippedApplicationsService implements ShippedApplications {

    private final Solutions solutions;
    private final Entitlements entitlements;
    private final ShippedPolicyKeeper keeper;

    ShippedApplicationsService(Solutions solutions, Entitlements entitlements, ShippedPolicyKeeper keeper) {
        this.solutions = solutions;
        this.entitlements = entitlements;
        this.keeper = keeper;
    }

    @Override
    public List<ShippedApplicationView> forMember(UUID membershipId) {
        List<ShippedApplicationView> views = new ArrayList<>();
        // Each feature is asked once per call, in the order the bundles stand in the grid (FA13A, ADR-0225).
        Map<String, Boolean> asked = new HashMap<>();
        Predicate<String> featureOn = feature -> asked.computeIfAbsent(feature, entitlements::enabled);
        for (SolutionBundle bundle : solutions.bundles()) {
            // The policies another application shares exist while any of the features they belong to is on.
            keeper.keep(bundle, featureOn);
            if (featureOn.test(bundle.feature())) {
                bundle.applications().forEach(application -> views.add(view(application, bundle.roles())));
            }
        }
        return views;
    }

    private static ShippedApplicationView view(SolutionBundle.Application application, Map<String, String> roles) {
        List<ShippedEntryView> entries = application.entries().stream().map(entry -> new ShippedEntryView(entry.kind(),
                entry.label(), blankToNull(entry.object()), blankToNull(entry.pageKey()),
                blankToNull(entry.readsObject()), entry.placeholder())).toList();
        return new ShippedApplicationView(application.apiName(), application.label(),
                blankToNull(application.iconKey()), blankToNull(application.group()), entries, roles);
    }

    private static String blankToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }
}
