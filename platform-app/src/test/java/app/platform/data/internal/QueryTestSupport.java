package app.platform.data.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** What the unit tests of the query stages share: an object with a field of every kind a query uses, and a member. */
final class QueryTestSupport {

    static final RecordProperties SETTINGS = new RecordProperties(new RecordProperties.Limits(262_144),
            new RecordProperties.Retention(true, Duration.ofDays(30), Duration.ofHours(1), 500),
            new RecordProperties.Index(true, 1000, 100, Duration.ofMinutes(5)),
            new RecordProperties.Query(50, 200, 4, 50, 100, 100, 20_000),
            new RecordProperties.Links(10_000, 1000));

    /** The fields of the test object that a member can read. */
    static final Set<String> READABLE = Set.of("id", "createdAt", "ownerId", "updatedAt", "name__c", "qty__c",
            "due__c", "seen__c", "done__c", "owner__c", "calc__c", "notes__c", "stage__c", "code__c");

    private QueryTestSupport() {
    }

    static FieldDefinition custom(String name, FieldType type, FieldConfiguration configuration, boolean indexed) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null,
                configuration, false, 0, indexed);
    }

    static FieldDefinition system(String name, FieldType type, FieldConfiguration configuration) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.SYSTEM, type, false, false, null,
                configuration, false, 0, false);
    }

    /** An object with a field of each kind, one the member may not read ({@code secret__c}), and the system fields. */
    static ObjectDefinition thing() {
        List<FieldDefinition> fields = new ArrayList<>();
        fields.add(system("id", FieldType.TEXT, new TextConfiguration(36)));
        fields.add(system("createdAt", FieldType.DATETIME, new NoConfiguration()));
        fields.add(system("updatedAt", FieldType.DATETIME, new NoConfiguration()));
        fields.add(system("ownerId", FieldType.LOOKUP, new ReferenceConfiguration("User")));
        fields.add(custom("name__c", FieldType.TEXT, new TextConfiguration(255), false));
        fields.add(custom("code__c", FieldType.TEXT, new TextConfiguration(255), true));
        fields.add(custom("qty__c", FieldType.NUMBER, new NumberConfiguration(10), false));
        fields.add(custom("due__c", FieldType.DATE, new NoConfiguration(), false));
        fields.add(custom("seen__c", FieldType.DATETIME, new NoConfiguration(), false));
        fields.add(custom("done__c", FieldType.BOOLEAN, new NoConfiguration(), false));
        fields.add(custom("stage__c", FieldType.TEXT, new TextConfiguration(20), false));
        fields.add(custom("owner__c", FieldType.LOOKUP, new ReferenceConfiguration("Target__c"), false));
        fields.add(custom("notes__c", FieldType.LONG_TEXT, new TextConfiguration(1000), false));
        fields.add(custom("calc__c", FieldType.FORMULA, new FormulaConfiguration("qty__c * 2", FieldType.NUMBER,
                true), false));
        fields.add(custom("secret__c", FieldType.TEXT, new TextConfiguration(255), false));
        return new ObjectDefinition("Thing__c", "Thing", "Things", "", DefinitionKind.CUSTOM, null, true, 0, fields);
    }

    /** What a member who may read everything but {@code secret__c} may do. */
    static ObjectAccess access() {
        return new ObjectAccess("Thing__c", EnumSet.of(ObjectAction.READ), READABLE, Set.of());
    }
}
