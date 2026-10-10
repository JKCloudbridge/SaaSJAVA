package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The guard behind "never delete or rename a released standard definition" (ADR-0059). The file
 * {@code src/test/resources/metadata/standard-baseline.txt} lists what has been released: every standard object, every
 * standard and system field with its type, and for the few things that must not move, the target of a reference, the
 * values of a picklist and the shape of an auto-number. This test fails when a definition file loses or renames
 * something in the list, changes its type or target, or removes a picklist value, and it also fails when a definition
 * file has something that is not in the list yet, so a new definition is a visible line in the same change.
 *
 * <p>To accept the new state after a deliberate change, run
 * {@code ./mvnw -pl platform-app test -Dtest=StandardMetadataBaselineTest -Dplatform.metadata.baseline.update=true}
 * and look at the difference in the baseline file before committing it. The recipes are in
 * {@code docs/metadata-guide.md}.
 */
class StandardMetadataBaselineTest {

    static final Path BASELINE = Path.of("src/test/resources/metadata/standard-baseline.txt");

    @Test
    void theDefinitionFilesKeepEverythingThatWasReleased() throws IOException {
        Map<String, String> current = lines();
        if (Boolean.getBoolean("platform.metadata.baseline.update")) {
            Files.createDirectories(BASELINE.getParent());
            Files.writeString(BASELINE, render(current), StandardCharsets.UTF_8);
        }
        Map<String, String> released = parse(Files.readString(BASELINE, StandardCharsets.UTF_8));

        List<String> broken = new ArrayList<>();
        released.forEach((name, detail) -> {
            String now = current.get(name);
            if (now == null) {
                broken.add(name + " was released and is gone: set 'retired: true' instead of deleting it, and never "
                        + "rename it");
            } else if (!compatible(detail, now)) {
                broken.add(name + " changed from [" + detail + "] to [" + now + "]: a released definition keeps its "
                        + "type, its target and its picklist values");
            }
        });
        List<String> unlisted = current.keySet().stream().filter(name -> !released.containsKey(name)).toList();

        assertThat(broken).as("released definitions that were removed or changed").isEmpty();
        assertThat(unlisted).as("definitions that are not in the baseline yet: run the update command in the "
                + "javadoc of this test and commit the result").isEmpty();
    }

    /** One line per object and per field, with the details that must not change. */
    static Map<String, String> lines() {
        StandardMetadata standard = StandardMetadataLoader.load();
        Map<String, String> lines = new LinkedHashMap<>();
        for (FieldDefinition field : standard.systemFields()) {
            lines.put("system." + field.apiName(), detail(field));
        }
        for (ObjectDefinition object : standard.objects()) {
            lines.put(object.apiName(), "managedBy=" + (object.managedBy() == null ? "none" : object.managedBy()));
            int skip = standard.systemFields().size();
            for (FieldDefinition field : object.fields().subList(skip, object.fields().size())) {
                lines.put(object.apiName() + "." + field.apiName(), detail(field));
            }
        }
        return lines;
    }

    private static String detail(FieldDefinition field) {
        FieldConfiguration configuration = field.configuration();
        String more = switch (configuration) {
            case ReferenceConfiguration reference -> " target=" + reference.targetObject();
            case PicklistConfiguration picklist -> " values=" + picklist.values().stream().map(PicklistValue::value)
                    .collect(Collectors.joining(","));
            case AutoNumberConfiguration auto -> " prefix=" + auto.prefix() + " width=" + auto.width();
            default -> "";
        };
        return field.type() + more;
    }

    /** Whether the current detail keeps everything the released one promised. */
    private static boolean compatible(String released, String now) {
        if (released.equals(now)) {
            return true;
        }
        int at = released.indexOf(" values=");
        if (at < 0 || !now.startsWith(released.substring(0, at) + " values=")) {
            return false;
        }
        List<String> kept = List.of(now.substring(now.indexOf(" values=") + " values=".length()).split(","));
        return List.of(released.substring(at + " values=".length()).split(",")).stream().allMatch(kept::contains);
    }

    private static String render(Map<String, String> lines) {
        StringBuilder text = new StringBuilder("# Released standard definitions (ADR-0059). Generated: see the javadoc "
                + "of StandardMetadataBaselineTest.\n");
        lines.forEach((name, detail) -> text.append(name).append(' ').append(detail).append('\n'));
        return text.toString();
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            lines.put(line.substring(0, space), line.substring(space + 1));
        }
        return lines;
    }
}
