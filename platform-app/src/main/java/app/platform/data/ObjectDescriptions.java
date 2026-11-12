package app.platform.data;

import app.platformapi.ObjectFormView;
import java.util.List;

/**
 * What the signed-in member may know about the objects of the organization and their fields (Sprint 30, ADR-0245): the
 * description the public API serves. It is the same answer the screens get, from the same code, so a description and
 * the rules a write is judged by cannot drift apart. Only objects the member may read are described and only the fields
 * they may read; anything else answers like an object that does not exist.
 */
public interface ObjectDescriptions {

    /**
     * One object the member may read.
     *
     * @param apiName the permanent name
     * @param label the label for one record
     * @param pluralLabel the label for many
     * @param kind {@code STANDARD} or {@code CUSTOM}
     * @param canCreate whether the member may create records of the object
     * @param canUpdate whether the member may change records of the object
     * @param canDelete whether the member may delete records of the object
     */
    record Described(String apiName, String label, String pluralLabel, String kind, boolean canCreate,
            boolean canUpdate, boolean canDelete) {
    }

    /**
     * The objects whose records the member may read and that the record store holds (an object a platform module owns,
     * such as a user, is not one of them), by API name.
     */
    List<Described> readable();

    /**
     * One object with the fields the member may read.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for the platform host, an object that does not exist, one
     *         a platform module owns and one the member may not read: all alike
     */
    ObjectFormView describe(String objectApiName);
}
