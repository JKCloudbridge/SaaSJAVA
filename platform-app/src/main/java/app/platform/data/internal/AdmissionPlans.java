package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platform.data.QueryPlan;
import app.platform.data.QueryPlan.Group;
import app.platform.data.QueryPlan.NoRecords;
import app.platform.data.QueryPlan.OwnedByAny;
import app.platform.data.QueryPlan.SharedWithAny;
import app.platform.data.RecordQuery;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Admission;
import app.platform.security.ObjectAccess;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the closed description of what a member may reach ({@link Admission}, from the security module) into the
 * condition of a statement ({@link QueryPlan.Node}) (Sprint 17, ADR-0115, ADR-0119). It is the only place that does:
 * the list, the query, the single read, the lookups and the answer for one record all call it, so they cannot differ.
 *
 * <p>The meaning is fixed by {@link Admission}. An owner set becomes a condition on the owner column, a share becomes
 * a condition on the explicit shares, a sharing rule's condition goes through the stages of a query (the same
 * analyzer and planner as a member's filter, but with every field readable: whether a member may <em>read</em> a field
 * has nothing to do with whether a rule that gives them the record may <em>test</em> it), and a combination becomes
 * an "or".
 *
 * <p>A stored condition that no longer passes the stages (a field changed type under it, which the metadata lifecycle
 * tries to prevent) gives nothing: the part fails closed and is logged by the rule, never by its text.
 */
@Component
class AdmissionPlans {

    private static final Logger LOG = LoggerFactory.getLogger(AdmissionPlans.class);

    private final QueryAnalyzer analyzer;
    private final QueryPlanner planner;
    private final IndexPolicy policy;

    AdmissionPlans(QueryAnalyzer analyzer, QueryPlanner planner, IndexPolicy policy) {
        this.analyzer = analyzer;
        this.planner = planner;
        this.policy = policy;
    }

    /**
     * The condition a record must meet to be admitted.
     *
     * @return the condition, or empty when the admission admits every record (no condition is needed)
     */
    Optional<QueryPlan.Node> conditionOf(ObjectDefinition object, Admission admission) {
        return Optional.ofNullable(node(object, admission));
    }

    private QueryPlan.Node node(ObjectDefinition object, Admission admission) {
        return switch (admission) {
            case Admission.Everything everything -> null;
            case Admission.Nothing nothing -> new NoRecords();
            case Admission.OwnedBy owned -> new OwnedByAny(sorted(owned.owners()));
            case Admission.SharedWith shared -> new SharedWithAny(sorted(shared.grantees()), shared.atLeast().value());
            case Admission.Meets meets -> criterion(object, meets);
            case Admission.AnyOf anyOf -> anyOf(object, anyOf);
        };
    }

    private QueryPlan.Node anyOf(ObjectDefinition object, Admission.AnyOf anyOf) {
        List<QueryPlan.Node> parts = new ArrayList<>();
        for (Admission part : anyOf.parts()) {
            QueryPlan.Node node = node(object, part);
            if (node == null) {
                // A part that admits every record admits every record: no condition at all.
                return null;
            }
            parts.add(node);
        }
        return new Group(false, parts);
    }

    private QueryPlan.Node criterion(ObjectDefinition object, Admission.Meets meets) {
        try {
            QueryFilter filter = FilterNodes.toFilter(meets.criterion(), "criterion");
            QueryAnalyzer.Analyzed analyzed = analyzer.analyze(object, readableEverything(object),
                    new RecordQuery(filter, List.of(), null, List.of(), 0, null));
            return planner.plan(analyzed, policy.completeSlots(object.apiName()), null, null, 1).plan().where();
        } catch (ApiException e) {
            LOG.warn("The condition of a sharing rule no longer passes the stages of a query and gives no access "
                    + "(rule {})", meets.rule());
            return new NoRecords();
        }
    }

    /** An access that reads every field: a sharing rule tests fields whatever the member may read. */
    static ObjectAccess readableEverything(ObjectDefinition object) {
        Set<String> fields = object.fields().stream().map(FieldDefinition::apiName).collect(Collectors.toSet());
        return new ObjectAccess(object.apiName(), Set.of(), fields, Set.of());
    }

    private static List<UUID> sorted(Set<UUID> ids) {
        return new ArrayList<>(new TreeSet<>(ids));
    }
}
