package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * How a form is laid out for the signed-in member (Sprint 19, ADR-0129): the page layout the object uses for their
 * profile and the record type, reduced to what they may see, or a layout the platform makes when the object has none.
 *
 * @param source {@code LAYOUT} when an administrator's page layout decided, {@code GENERATED} when the object has none
 *        and every field the member may see is listed in one block (and the system fields in another)
 * @param layout the API name of the page layout, or null when generated
 * @param sections the blocks, none empty
 */
public record FormLayoutView(@NotNull String source, String layout, @NotNull List<FormSectionView> sections) {

    /** Copies the list. */
    public FormLayoutView {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
