package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The union of the field permissions with the fields a record type offers, on thousands of random inputs (Sprint 15,
 * ADR-0092, ADR-0093): a field named in a write is accepted exactly when the member may edit it <em>and</em> the type
 * offers it, a field the member may not read is refused with the answer of a field that does not exist whatever else is
 * true, and the answer does not depend on the order the fields are named in. Each property is a sentence of the
 * documented rule, written out again here independently of the code under test.
 */
class FieldWritePropertyTest {

    private static final List<String> NAMES = List.of("a__c", "b__c", "c__c", "d__c", "e__c", "f__c");

    private static final ObjectDefinition THING = new ObjectDefinition("Thing__c", "Thing", "Things", "",
            DefinitionKind.CUSTOM, null, true, 0, NAMES.stream().map(FieldWritePropertyTest::field).toList());

    private final FieldSecurity security = new FieldSecurity(null);
    private final RecordTypeCheck types = new RecordTypeCheck(null);

    private static FieldDefinition field(String name) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.TEXT, false, false,
                null, new FieldConfiguration.TextConfiguration(10), false, 0);
    }

    @Provide
    Arbitrary<Set<String>> someFields() {
        return Arbitraries.of(NAMES).set().ofMaxSize(NAMES.size());
    }

    @Provide
    Arbitrary<List<String>> writes() {
        return Arbitraries.of(NAMES).list().uniqueElements().ofMinSize(1).ofMaxSize(NAMES.size());
    }

    private static ObjectAccess access(Set<String> editable, Set<String> readOnly) {
        Set<String> readable = new HashSet<>(editable);
        readable.addAll(readOnly);
        return new ObjectAccess("Thing__c", Set.of(ObjectAction.READ, ObjectAction.CREATE, ObjectAction.UPDATE),
                readable, editable);
    }

    private static RecordType type(boolean all, Set<String> offered) {
        return new RecordType("Thing__c", "T__c", "T", "", true, false, "", all, List.copyOf(offered), Map.of(), 0);
    }

    /** The places that have a problem when the fields are written, by the two steps in the order of the pipeline. */
    private Set<String> refused(ObjectAccess access, RecordType type, List<String> written) {
        Findings findings = new Findings();
        List<String> allowed = security.checkWrite(THING, access, written, findings);
        types.checkOffered(type, THING, allowed, findings);
        return findings.places();
    }

    @Property
    boolean aFieldIsAcceptedExactlyWhenTheMemberMayEditItAndTheTypeOffersIt(
            @ForAll("someFields") Set<String> editable, @ForAll("someFields") Set<String> readOnly,
            @ForAll("someFields") Set<String> offered, @ForAll boolean allFields,
            @ForAll("writes") List<String> written) {
        Set<String> expected = new HashSet<>();
        for (String name : written) {
            boolean mayEdit = editable.contains(name);
            boolean typeOffers = allFields || offered.contains(name);
            if (!mayEdit || !typeOffers) {
                expected.add(name);
            }
        }

        return refused(access(editable, readOnly), type(allFields, offered), written).equals(expected);
    }

    @Property
    boolean aFieldTheMemberCannotReadIsRefusedLikeAFieldThatDoesNotExist(
            @ForAll("someFields") Set<String> editable, @ForAll("someFields") Set<String> readOnly,
            @ForAll("writes") List<String> written) {
        ObjectAccess access = access(editable, readOnly);
        Findings findings = new Findings();
        security.checkWrite(THING, access, written, findings);
        Findings unknown = new Findings();
        security.checkWrite(THING, access, List.of("nope__c"), unknown);

        for (String name : written) {
            if (!access.readableFields().contains(name)
                    && !findings.at(name).equals(unknown.at("nope__c"))) {
                return false;
            }
        }
        return true;
    }

    @Property
    boolean theAnswerDoesNotDependOnTheOrderTheFieldsAreNamedIn(@ForAll("someFields") Set<String> editable,
            @ForAll("someFields") Set<String> readOnly, @ForAll("someFields") Set<String> offered,
            @ForAll("writes") List<String> written) {
        List<String> reversed = new ArrayList<>(written);
        Collections.reverse(reversed);
        RecordType type = type(false, offered);
        ObjectAccess access = access(editable, readOnly);

        return refused(access, type, written).equals(refused(access, type, reversed));
    }

    @Property
    void editingNeverMakesAFieldUnreadableAndReadingNeverMakesItEditable(@ForAll("someFields") Set<String> editable,
            @ForAll("someFields") Set<String> readOnly) {
        ObjectAccess access = access(editable, readOnly);

        assertThat(access.readableFields()).containsAll(access.editableFields());
        for (String name : readOnly) {
            if (!editable.contains(name)) {
                assertThat(access.editableFields()).doesNotContain(name);
            }
        }
    }
}
