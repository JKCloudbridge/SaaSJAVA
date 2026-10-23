package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platform.data.QueryFilterNodes;
import app.platformapi.ApiException;
import app.platformapi.QueryFilterNode;

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
        return QueryFilterNodes.toFilter(node, place);
    }
}
