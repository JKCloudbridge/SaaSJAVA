package app.platform.inspection.internal;

import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import app.platform.sharedkernel.solution.NumberSeriesDeclarations;
import app.platform.sharedkernel.solution.ObjectRoleDeclarations;
import app.platform.sharedkernel.solution.ObjectRoles;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sets of records the unit tests of the cluster work with (FA13B, ADR-0226): the sets of the two shipped
 * applications of the kind, built through the same {@link RecordSets} the application uses, from the same kind of
 * declarations the solution files give, with no database and no files.
 */
final class RecordSetsFixture {

    /** The objects of the Quality Management System application. */
    static final RecordSet QMS = new RecordSet("InspectionTemplate", "InspectionFill", "InspectionChangeRequest",
            "document");
    /** The objects of the Environment Management application. */
    static final RecordSet ENVIRONMENT = new RecordSet("EnvironmentTemplate", "EnvironmentFill",
            "EnvironmentChangeRequest", "environment-document");

    private RecordSetsFixture() {
    }

    /** Both sets, each shown by one application, every application available. */
    static RecordSets both() {
        return sets(List.of(roles("inspection", "inspection", QMS), roles("environment-management",
                "environment-management", ENVIRONMENT)), List.of("inspection", "environment-management"));
    }

    /** Only the first set. */
    static RecordSets quality() {
        return sets(List.of(roles("inspection", "inspection", QMS)), List.of("inspection"));
    }

    /** The declarations given, of which the applications named are available to the organization. */
    static RecordSets sets(List<ObjectRoles> declared, List<String> available) {
        return sets(declared, available, new TenantContexts());
    }

    /** The same, asking the given tenant contexts who the caller is. */
    static RecordSets sets(List<ObjectRoles> declared, List<String> available, TenantContexts contexts) {
        ObjectRoleDeclarations declarations = new ObjectRoleDeclarations() {
            @Override
            public List<ObjectRoles> declared() {
                return declared;
            }

            @Override
            public Optional<ObjectRoles> available(String application) {
                return declared.stream().filter(one -> one.application().equals(application))
                        .filter(one -> available.contains(application)).findFirst();
            }
        };
        List<NumberSeriesDeclaration> series = new ArrayList<>();
        for (ObjectRoles one : declared) {
            String template = one.object(RecordSets.TEMPLATE);
            String key = template.equals(QMS.template()) ? QMS.series()
                    : template.equals(ENVIRONMENT.template()) ? ENVIRONMENT.series() : template + "-series";
            if (series.stream().noneMatch(existing -> existing.object().equals(template))) {
                series.add(new NumberSeriesDeclaration(key, template, "number", "category", Map.of()));
            }
        }
        NumberSeriesDeclarations numbers = () -> series;
        return new RecordSets(declarations, numbers, contexts);
    }

    /** The parts of an application whose objects are those of the set. */
    static ObjectRoles roles(String application, String feature, RecordSet set) {
        return new ObjectRoles(application, feature, Map.of(RecordSets.TEMPLATE, set.template(),
                RecordSets.FILL, set.fill(), RecordSets.REQUEST, set.request()));
    }
}
