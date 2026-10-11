package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The answer to "is this expression valid?" (Sprint 13): whether it is, where it is wrong when it is not, the type it
 * gives and the fields it uses.
 *
 * @param valid whether the expression can be saved
 * @param problem what is wrong and where, or null when it is valid
 * @param type the type it gives ({@code BOOLEAN}, {@code NUMBER}, {@code TEXT}, {@code DATE}, {@code DATETIME},
 *        {@code TIME}), or null when it could not be worked out
 * @param fields the API names of the fields it uses, alphabetical (what could be found even when it is not valid)
 * @param usesClock whether it asks for the moment of the save (only a validation rule may)
 */
public record ExpressionCheckView(@NotNull Boolean valid, ExpressionProblemView problem, String type,
        @NotNull List<String> fields, @NotNull Boolean usesClock) {

    /** Copies the list. */
    public ExpressionCheckView {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
