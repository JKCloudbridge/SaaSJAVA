package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/standard-objects.md}, the human list of every standard object and field, equal to the definition
 * files (ADR-0059), so the documentation cannot drift from what the platform really defines. The document is
 * generated; to refresh it after a change run
 * {@code ./mvnw -pl platform-app test -Dtest=StandardObjectsDocTest -Dplatform.metadata.doc.update=true}.
 */
class StandardObjectsDocTest {

    private static final Path DOCUMENT = Path.of("../docs/standard-objects.md");

    @Test
    void theStandardObjectsDocumentListsExactlyWhatTheFilesDefine() throws IOException {
        String expected = render(StandardMetadataLoader.load());
        if (Boolean.getBoolean("platform.metadata.doc.update")) {
            Files.writeString(DOCUMENT, expected, StandardCharsets.UTF_8);
        }

        assertThat(Files.readString(DOCUMENT, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("docs/standard-objects.md is generated from the definition files: run the update command in "
                        + "the javadoc of this test and commit the result")
                .isEqualTo(expected);
    }

    static String render(StandardMetadata standard) {
        StringBuilder out = new StringBuilder();
        out.append("# Standard objects and fields\n\n");
        out.append("This file is generated from the definition files in "
                + "`platform-app/src/main/resources/metadata/standard`. Do not edit it by hand: change the files and "
                + "refresh it as described in [metadata-guide.md](metadata-guide.md). A test fails when the two "
                + "differ.\n\n");
        out.append("Every object also has the system fields listed first. Organizations add their own fields to an "
                + "object where *Own fields* says yes; their names always end in `__c`.\n\n");
        out.append("## System fields (every object)\n\n");
        table(out, standard.systemFields());
        for (ObjectDefinition object : standard.objects()) {
            out.append("\n## ").append(object.apiName()).append("\n\n");
            out.append(object.description()).append("\n\n");
            out.append("- Label: ").append(object.label()).append(" / ").append(object.pluralLabel()).append('\n');
            out.append("- Records managed by: ")
                    .append(object.managedBy() == null ? "the organization (ordinary data)" : object.managedBy())
                    .append('\n');
            out.append("- Own fields: ").append(object.extensible() ? "yes" : "no").append("\n\n");
            table(out, object.fields().subList(standard.systemFields().size(), object.fields().size()));
        }
        return out.toString();
    }

    private static void table(StringBuilder out, java.util.List<FieldDefinition> fields) {
        out.append("| API name | Label | Type | Required | Unique | Details |\n");
        out.append("|----------|-------|------|----------|--------|---------|\n");
        for (FieldDefinition field : fields) {
            out.append("| `").append(field.apiName()).append("` | ").append(field.label()).append(" | ")
                    .append(field.type()).append(" | ").append(field.required() ? "yes" : "").append(" | ")
                    .append(field.unique() ? "yes" : "").append(" | ").append(details(field)).append(" |\n");
        }
    }

    private static String details(FieldDefinition field) {
        FieldConfiguration configuration = field.configuration();
        String text = switch (configuration) {
            case TextConfiguration t -> "up to " + t.maxLength() + " characters";
            case DecimalConfiguration d -> d.precision() + " digits, " + d.scale() + " after the point";
            case ReferenceConfiguration r -> "points to `" + r.targetObject() + "`";
            case PicklistConfiguration p -> "values: " + p.values().stream().map(PicklistValue::value)
                    .collect(Collectors.joining(", "));
            case AutoNumberConfiguration a -> "`" + a.prefix() + "` + number, " + a.width() + " digits, from "
                    + a.startAt();
            default -> "";
        };
        String retired = field.retired() ? "RETIRED. " : "";
        String defaultText = field.defaultValue() == null ? "" : (text.isEmpty() ? "" : "; ") + "default `"
                + field.defaultValue() + "`";
        return retired + text + defaultText;
    }
}
