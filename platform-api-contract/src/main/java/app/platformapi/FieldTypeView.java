package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One field type an organization can choose, with what it takes (Sprint 10). The object manager builds its form from
 * this, so it holds no list of types of its own.
 *
 * @param type the code, for example {@code PICKLIST}
 * @param label the name people read
 * @param description what the type is for
 * @param settings the names of the settings the type takes (see {@link FieldSettings})
 * @param allowsRequired whether a field of this type can be required
 * @param allowsUnique whether a field of this type can be unique
 * @param allowsDefault whether a field of this type can have a default value
 * @param calculated whether the value is worked out by the platform, not typed (formula, auto-number)
 * @param formulaResult whether a formula can yield a value of this type
 */
public record FieldTypeView(@NotNull String type, @NotNull String label, @NotNull String description,
        @NotNull List<String> settings, @NotNull Boolean allowsRequired, @NotNull Boolean allowsUnique,
        @NotNull Boolean allowsDefault,
        @NotNull Boolean calculated, @NotNull Boolean formulaResult) {

    public FieldTypeView {
        settings = settings == null ? List.of() : List.copyOf(settings);
    }
}
