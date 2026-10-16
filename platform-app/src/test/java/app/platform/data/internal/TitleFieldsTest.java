package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which field names a record for people (Sprint 19, ADR-0129): the one rule, tried in order, for what a member reads.
 */
class TitleFieldsTest {

    private static FieldDefinition field(String name, FieldType type, boolean required, boolean searchable,
            boolean retired, DefinitionKind kind) {
        return new FieldDefinition("Thing__c", name, name, "", kind, type, required, false, null,
                type == FieldType.AUTO_NUMBER ? new AutoNumberConfiguration("", 1, 1)
                        : type == FieldType.TEXT || type == FieldType.EMAIL || type == FieldType.LONG_TEXT
                                ? new TextConfiguration(100) : new NoConfiguration(),
                retired, 0, false, false, searchable);
    }

    private static FieldDefinition text(String name) {
        return field(name, FieldType.TEXT, false, false, false, DefinitionKind.CUSTOM);
    }

    private static ObjectDefinition object(FieldDefinition... fields) {
        List<FieldDefinition> all = new ArrayList<>();
        all.add(field("sequence", FieldType.AUTO_NUMBER, false, false, false, DefinitionKind.SYSTEM));
        all.addAll(List.of(fields));
        return new ObjectDefinition("Thing__c", "Thing", "Things", "", DefinitionKind.CUSTOM, null, true, 0, all);
    }

    private static String title(ObjectDefinition object, String... readable) {
        return TitleFields.of(object, Set.of(readable)).map(FieldDefinition::apiName).orElse(null);
    }

    @Test
    void aSearchableTextFieldNamesTheRecordBeforeAnythingElse() {
        ObjectDefinition thing = object(text("first__c"),
                field("second__c", FieldType.TEXT, true, false, false, DefinitionKind.CUSTOM),
                field("third__c", FieldType.EMAIL, false, true, false, DefinitionKind.CUSTOM));

        assertThat(title(thing, "first__c", "second__c", "third__c", "sequence")).isEqualTo("third__c");
    }

    @Test
    void otherwiseTheFirstRequiredTextFieldAndOtherwiseTheFirstTextField() {
        ObjectDefinition thing = object(text("first__c"),
                field("second__c", FieldType.TEXT, true, false, false, DefinitionKind.CUSTOM));

        assertThat(title(thing, "first__c", "second__c")).isEqualTo("second__c");
        assertThat(title(thing, "first__c")).isEqualTo("first__c");
    }

    @Test
    void aFieldTheMemberMayNotReadNeverNamesARecordForThem() {
        ObjectDefinition thing = object(
                field("secret__c", FieldType.TEXT, true, true, false, DefinitionKind.CUSTOM), text("plain__c"));

        assertThat(title(thing, "plain__c")).isEqualTo("plain__c");
        assertThat(title(thing, "secret__c", "plain__c")).as("another member may read it").isEqualTo("secret__c");
    }

    @Test
    void aRetiredFieldASystemFieldAndAFieldThatIsNotShortTextAreNeverUsed() {
        ObjectDefinition thing = object(
                field("old__c", FieldType.TEXT, true, true, true, DefinitionKind.CUSTOM),
                field("notes__c", FieldType.LONG_TEXT, true, true, false, DefinitionKind.CUSTOM),
                field("count__c", FieldType.NUMBER, true, true, false, DefinitionKind.CUSTOM),
                field("name", FieldType.TEXT, true, true, false, DefinitionKind.SYSTEM));

        assertThat(title(thing, "old__c", "notes__c", "count__c", "name")).isNull();
    }

    @Test
    void theAutoNumberNamesARecordWhenNothingElseCan() {
        ObjectDefinition thing = object(field("count__c", FieldType.NUMBER, false, false, false,
                DefinitionKind.CUSTOM));

        assertThat(title(thing, "count__c", "sequence")).isEqualTo("sequence");
        assertThat(title(thing, "count__c")).as("not readable either").isNull();
    }
}
