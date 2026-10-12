package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The definition files of the standard objects (ADR-0059): the real ones are sound, and every kind of mistake in a file
 * stops the application from starting with a message that names the place.
 */
class StandardMetadataLoaderTest {

    private static final String SYSTEM_FIELDS = """
            fields:
              - { apiName: id, label: Record ID, type: TEXT, unique: true, settings: { maxLength: 36 } }
              - { apiName: ownerId, label: Owner, type: LOOKUP, settings: { targetObject: Thing } }
            """;

    @Test
    void theRealDefinitionsLoadAndAreComplete() {
        StandardMetadata standard = StandardMetadataLoader.load();

        assertThat(standard.objects()).extracting(ObjectDefinition::apiName).containsExactlyInAnyOrder(
                "Account", "AccessPolicy", "AccessPolicyAssignment", "Case", "Contact", "LicenceType",
                "Opportunity", "Profile", "RecordType", "Role", "User").doesNotHaveDuplicates();
        assertThat(standard.objects()).allSatisfy(object -> {
            assertThat(object.kind()).isEqualTo(DefinitionKind.STANDARD);
            assertThat(object.fields().subList(0, standard.systemFields().size()))
                    .as("the system fields come first on " + object.apiName())
                    .extracting(FieldDefinition::apiName)
                    .containsExactlyElementsOf(standard.systemFields().stream().map(FieldDefinition::apiName)
                            .toList());
            assertThat(object.fields()).allSatisfy(field -> assertThat(field.objectApiName())
                    .isEqualTo(object.apiName()));
        });
    }

    @Test
    void everyObjectAndFieldHasALabelAndAnApiNameInTheDocumentedShape() {
        StandardMetadata standard = StandardMetadataLoader.load();

        assertThat(standard.objects()).allSatisfy(object -> {
            assertThat(object.label()).isNotBlank();
            assertThat(object.pluralLabel()).isNotBlank();
            assertThat(object.apiName()).matches("[A-Z][A-Za-z0-9]*").doesNotContain("__");
            assertThat(object.fields()).allSatisfy(field -> {
                assertThat(field.label()).isNotBlank();
                assertThat(field.apiName()).matches("[a-z][A-Za-z0-9]*");
            });
        });
    }

    @Test
    void theSystemFieldsAreTheOnesEveryObjectHasAndAreProtected() {
        StandardMetadata standard = StandardMetadataLoader.load();

        assertThat(standard.systemFields()).extracting(FieldDefinition::apiName).containsExactly(
                "id", "sequence", "description", "ownerId", "createdAt", "createdById", "updatedAt", "updatedById");
        assertThat(standard.systemFields()).allSatisfy(field -> assertThat(field.kind())
                .isEqualTo(DefinitionKind.SYSTEM));
        FieldDefinition sequence = standard.systemFields().get(1);
        assertThat(sequence.type()).isEqualTo(FieldType.AUTO_NUMBER);
        assertThat(sequence.unique()).as("an auto-number is unique by nature").isTrue();
    }

    @Test
    void theJunctionHasMasterDetailOnBothSidesAndTheCaseNumberCountsUp() {
        StandardMetadata standard = StandardMetadataLoader.load();

        ObjectDefinition junction = standard.object("AccessPolicyAssignment").orElseThrow();
        assertThat(junction.field("assigneeId").orElseThrow().type()).isEqualTo(FieldType.MASTER_DETAIL);
        assertThat(junction.field("assigneeId").orElseThrow().required()).isTrue();
        assertThat(junction.field("assigneeId").orElseThrow().configuration())
                .isEqualTo(FieldConfiguration.ReferenceConfiguration.defaultsFor(FieldType.MASTER_DETAIL, "User"));
        assertThat(junction.field("accessPolicyId").orElseThrow().configuration())
                .isEqualTo(FieldConfiguration.ReferenceConfiguration.defaultsFor(FieldType.MASTER_DETAIL,
                        "AccessPolicy"));
        assertThat(junction.managedBy()).isEqualTo("security");
        assertThat(junction.extensible()).isFalse();

        FieldDefinition caseNumber = standard.object("Case").orElseThrow().field("caseNumber").orElseThrow();
        assertThat(caseNumber.configuration())
                .isEqualTo(new FieldConfiguration.AutoNumberConfiguration("CASE-", 1, 6));
    }

    @Test
    void everyLookupOfTheRealDefinitionsPointsToAStandardObject() {
        StandardMetadata standard = StandardMetadataLoader.load();
        List<String> names = standard.objects().stream().map(ObjectDefinition::apiName).toList();

        standard.objects().forEach(object -> object.fields().stream()
                .filter(field -> field.type().isReference())
                .forEach(field -> assertThat(names).contains(
                        ((FieldConfiguration.ReferenceConfiguration) field.configuration()).targetObject())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mistakes")
    void aMistakeInAFileStopsTheLoadingAndNamesThePlace(String name, String file, String expected, @TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("_system-fields.yml"), SYSTEM_FIELDS);
        Files.writeString(dir.resolve("Thing.yml"), file);

        assertThatThrownBy(() -> StandardMetadataLoader.load("file:" + dir.toUri().getPath() + "*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expected);
    }

    static Stream<Arguments> mistakes() {
        String head = "apiName: Thing\nlabel: Thing\npluralLabel: Things\n";
        return Stream.of(
                Arguments.of("an unknown key", head + "colour: red\nfields: []\n", "unknown key"),
                Arguments.of("an unknown field key", head + "fields:\n  - { apiName: a, label: A, type: TEXT, "
                        + "colur: red }\n", "unknown key"),
                Arguments.of("a file name that does not match", "apiName: Other\nlabel: O\npluralLabel: Os\n",
                        "must be called Other.yml"),
                Arguments.of("an api name with an underscore", "apiName: Thing_x\nlabel: T\npluralLabel: Ts\n",
                        "capital letter"),
                Arguments.of("a field api name that ends in the custom ending", head
                        + "fields:\n  - { apiName: a__c, label: A, type: TEXT }\n", "lower case letter"),
                Arguments.of("a field type that does not exist", head
                        + "fields:\n  - { apiName: a, label: A, type: WIZARD }\n", "not a field type"),
                Arguments.of("a lookup to an object that does not exist", head
                        + "fields:\n  - { apiName: a, label: A, type: LOOKUP, settings: { targetObject: Ghost } }\n",
                        "This object does not exist"),
                Arguments.of("a master-detail to its own object", head
                        + "fields:\n  - { apiName: a, label: A, type: MASTER_DETAIL, "
                        + "settings: { targetObject: Thing } }\n", "cannot point to its own object"),
                Arguments.of("the same field twice", head + "fields:\n  - { apiName: ab, label: A, type: TEXT }\n"
                        + "  - { apiName: aB, label: B, type: TEXT }\n", "used twice"),
                Arguments.of("a field that repeats a system field", head
                        + "fields:\n  - { apiName: id, label: A, type: TEXT }\n", "used twice"),
                Arguments.of("a default outside the picklist", head
                        + "fields:\n  - { apiName: a, label: A, type: PICKLIST, default: Z, settings: "
                        + "{ values: [ { value: X, label: X } ] } }\n", "active values"),
                Arguments.of("a setting the type does not take", head
                        + "fields:\n  - { apiName: a, label: A, type: BOOLEAN, settings: { maxLength: 5 } }\n",
                        "not used by a field of this type"),
                Arguments.of("an owner that is not known", head + "managedBy: nobody\nfields: []\n", "managedBy"),
                Arguments.of("a label that is empty", "apiName: Thing\nlabel: ' '\npluralLabel: Ts\n", "is empty"),
                Arguments.of("a flag written as text", head + "extensible: maybe\nfields: []\n",
                        "must be true or false"));
    }

    @Test
    void aMissingSystemFieldsFileStopsTheLoading(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Thing.yml"), "apiName: Thing\nlabel: T\npluralLabel: Ts\n");

        assertThatThrownBy(() -> StandardMetadataLoader.load("file:" + dir.toUri().getPath() + "*.yml"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("_system-fields.yml");
    }
}
