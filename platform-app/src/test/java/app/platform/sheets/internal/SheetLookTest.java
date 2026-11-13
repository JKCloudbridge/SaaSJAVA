package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The closed lists of the look (SH1A, ADR-0251) are written twice, in the schema the API publishes and in
 * {@link SheetLook}; this test keeps them one list. A key added to one and not to the other would be accepted by the
 * server and refused by the generated client, or the reverse.
 */
class SheetLookTest {

    private static final JsonNode SCHEMAS = new SheetSchemas().publishedSchemas();

    private static List<String> enumOf(JsonNode schema) {
        List<String> values = new ArrayList<>();
        schema.path("enum").forEach(value -> values.add(value.asString()));
        return values;
    }

    @Test
    void theFontsAreTheSchemasFonts() {
        assertThat(enumOf(SCHEMAS.path("SheetLook").path("properties").path("font")))
                .containsExactlyElementsOf(SheetLook.FONTS);
        assertThat(SheetLook.FONTS).hasSize(16).doesNotHaveDuplicates();
    }

    @Test
    void theNumberFormatsAreTheSchemasNumberFormats() {
        assertThat(enumOf(SCHEMAS.path("SheetLook").path("properties").path("numberFormat")))
                .containsExactlyElementsOf(SheetLook.NUMBER_FORMATS);
        assertThat(SheetLook.NUMBER_FORMATS).hasSize(10);
    }

    @Test
    void theBorderStylesAreTheSchemasBorderStyles() {
        assertThat(enumOf(SCHEMAS.path("SheetBorderStyle"))).containsExactlyElementsOf(SheetLook.BORDER_STYLES);
        assertThat(SheetLook.BORDER_STYLES).hasSize(6);
    }

    @Test
    void theVerticalAlignmentsAndTheSizesAreTheSchemas() {
        JsonNode look = SCHEMAS.path("SheetLook").path("properties");
        assertThat(enumOf(look.path("verticalAlignment"))).containsExactlyElementsOf(SheetLook.VERTICAL);
        assertThat(look.path("size").path("minimum").asInt()).isEqualTo(SheetLook.LEAST_SIZE);
        assertThat(look.path("size").path("maximum").asInt()).isEqualTo(SheetLook.MOST_SIZE);
    }

    @Test
    void aLookThatSaysOnlyWhatTheAbsenceSaysIsNothing() {
        SheetLook plain = new SheetLook(null, null, null, null, false, "BOTTOM", new SheetLook.Borders(null, null, null,
                null), "GENERAL");

        assertThat(plain.normalized()).isNull();
        assertThat(new SheetLook(null, null, "#ABCDEF", null, false, null, null, null).normalized().colour())
                .isEqualTo("#abcdef");
    }

    @Test
    void aLookPrintsNoDesign() {
        assertThat(new SheetLook("SANS", 12, "#112233", null, true, null, null, null))
                .hasToString("SheetLook[redacted]");
    }
}
