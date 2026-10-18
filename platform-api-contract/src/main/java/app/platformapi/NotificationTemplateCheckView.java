package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * What a text would look like, and what is wrong with it (Sprint 25, ADR-0155). The preview uses sample labels, never a
 * value of a record, and the problems never repeat the text.
 *
 * @param problems by place ({@code title}, {@code body}), what is wrong; empty when the text can be saved
 * @param previewTitle the title with sample labels in place of the placeholders, or null when it is not valid
 * @param previewBody the body with sample labels, or null when it is not valid
 */
public record NotificationTemplateCheckView(@NotNull Map<String, List<String>> problems, String previewTitle,
        String previewBody) {

    /** Copies the map. */
    public NotificationTemplateCheckView {
        problems = Map.copyOf(problems);
    }
}
