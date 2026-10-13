package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platformapi.ApiException;
import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the filter of the API ({@link QueryFilterNode}) into the filter of the record store ({@link QueryFilter}). The
 * request of a query and the condition of a criteria-based sharing rule (Sprint 17) are the same model, so they are
 * read by the same code and checked by the same stages.
 */
final class FilterNodes {

    private FilterNodes() {
    }

    /**
     * The filter for a node of the API.
     *
     * @param place where the node is in the request, for the problems it causes
     * @throws ApiException {@code VALIDATION_ERROR} for a comparison that is not in the list or a value that is missing
     */
    static QueryFilter toFilter(QueryFilterNode node, String place) {
        QueryFilter.Op op;
        try {
            op = QueryFilter.Op.valueOf(node.op());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(place + ".op", "Choose a comparison from the list.");
        }
        List<QueryFilter> items = new ArrayList<>();
        if (node.items() != null) {
            for (int index = 0; index < node.items().size(); index++) {
                items.add(toFilter(node.items().get(index), place + ".items[" + index + "]"));
            }
        }
        List<Object> values = node.values() == null ? List.of() : node.values();
        if (values.stream().anyMatch(value -> value == null)) {
            throw ApiException.validation(place + ".values", "A value is missing.");
        }
        return new QueryFilter(op, items, node.field(), values);
    }
}
