package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The objects of Management Core are copies of the Quality Management System's on purpose (MC1, ADR-0257): the
 * functions read these field names. This fails the build when a field the functions read is added to, removed from or
 * changed in one application and not the other, with no database.
 */
class ManagementCoreParityTest {

    private static final Map<String, String> PAIRS = Map.of("InspectionTemplate", "ManagementTemplate",
            "InspectionFill", "ManagementFill", "InspectionChangeRequest", "ManagementChangeRequest");

    private static List<String> shape(ObjectDefinition object) {
        return object.fields().stream().map(ManagementCoreParityTest::describe).toList();
    }

    private static String describe(FieldDefinition field) {
        return field.apiName() + ":" + field.type() + ":required=" + field.required() + ":unique=" + field.unique()
                + ":indexed=" + field.indexed() + ":platformWritten=" + field.platformWritten()
                + ":organizationValues=" + field.organizationValues() + ":codeValues=" + field.codeValues();
    }

    @Test
    void everyObjectOfTheNewApplicationHasTheFieldsOfItsCounterpart() {
        StandardMetadata standard = StandardMetadataLoader.load();

        PAIRS.forEach((quality, environment) -> {
            ObjectDefinition one = standard.object(quality).orElseThrow();
            ObjectDefinition other = standard.object(environment).orElseThrow();
            assertThat(shape(other)).as(environment).isEqualTo(shape(one));
            assertThat(other.retainRecords()).as(environment).isEqualTo(one.retainRecords());
            assertThat(other.sharingDefault()).as(environment).isEqualTo(one.sharingDefault());
            assertThat(other.extensible()).as(environment).isEqualTo(one.extensible());
        });
    }

    @Test
    void theNamesOfTheTwoApplicationsDoNotOverlap() {
        StandardMetadata standard = StandardMetadataLoader.load();

        List<String> names = standard.objects().stream().map(ObjectDefinition::apiName)
                .filter(name -> PAIRS.containsKey(name) || PAIRS.containsValue(name)).collect(Collectors.toList());

        assertThat(names).hasSize(6).doesNotHaveDuplicates();
    }
}
