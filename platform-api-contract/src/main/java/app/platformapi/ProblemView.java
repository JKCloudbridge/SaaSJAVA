package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One reason a change set cannot be published or a release cannot be rolled back (Sprint 11).
 *
 * @param kind {@code RULE} (the change itself breaks a rule), {@code DEPENDENCY} (something still needs what the change
 *        removes or changes), {@code CONFLICT} (someone changed the same thing since) or {@code RECORDS} (records or
 *        values would be lost)
 * @param position the change it comes from (1 is first), or null when it is about the result as a whole
 * @param objectApiName the object it is about
 * @param itemApiName the field or record type it is about, or null
 * @param dependent what needs the thing, named precisely (for example a record type of an object), or null
 * @param message what is wrong, in words a person can act on
 * @param fields for a rule problem, the fields of the request that are wrong with their problems
 */
public record ProblemView(@NotNull String kind, Integer position, String objectApiName, String itemApiName,
        String dependent, @NotNull String message, @NotNull List<String> fields) {

    /** Copies the list. */
    public ProblemView {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
