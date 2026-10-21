package app.platform.data.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The stage of a dependent picklist on thousands of random rules (FA2A, ADR-0177). Each property is a sentence of the
 * documented rule, written out again here independently of the code under test: a create is accepted exactly when the
 * pair is a pair of the rule; a record that holds any pair at all is never blocked by an edit that leaves the pair
 * alone;
 * what the record type narrows is the stage before this one's, so the intersection of the two holds.
 */
class DependentValuesPropertyTest {

    private static final List<String> CONTROLLING = List.of("A", "B", "C", "D");
    private static final List<String> DEPENDENT = List.of("w", "x", "y", "z");

    private final DependentValues stage = new DependentValues();

    @Provide
    Arbitrary<Map<String, List<String>>> rules() {
        Arbitrary<List<String>> row = Arbitraries.of(DEPENDENT).list().uniqueElements().ofMaxSize(DEPENDENT.size());
        return Arbitraries.of(CONTROLLING).list().uniqueElements().ofMaxSize(CONTROLLING.size()).flatMap(keys ->
                row.list().ofSize(keys.size()).map(rows -> {
                    Map<String, List<String>> rule = new LinkedHashMap<>();
                    for (int i = 0; i < keys.size(); i++) {
                        rule.put(keys.get(i), rows.get(i));
                    }
                    return rule;
                }));
    }

    private static ObjectDefinition object(Map<String, List<String>> rule) {
        return new ObjectDefinition("Doc", "Doc", "Docs", "", DefinitionKind.STANDARD, null, false, 0, List.of(
                picklist("kind", CONTROLLING, null), picklist("area", DEPENDENT, new Dependency("kind", rule)),
                new FieldDefinition("Doc", "title", "Title", "", DefinitionKind.STANDARD, FieldType.TEXT, false,
                        false, null, new TextConfiguration(20), false, 0)));
    }

    private static FieldDefinition picklist(String name, List<String> values, Dependency dependency) {
        return new FieldDefinition("Doc", name, name, "", DefinitionKind.STANDARD, FieldType.PICKLIST, false, false,
                null, new PicklistConfiguration(values.stream().map(value -> new PicklistValue(value, value, true))
                        .toList(), dependency), false, 0);
    }

    private static ObjectAccess access() {
        Set<String> all = Set.of("kind", "area", "title");
        return new ObjectAccess("Doc", Set.of(ObjectAction.READ, ObjectAction.CREATE, ObjectAction.UPDATE), all, all);
    }

    private boolean refused(Map<String, List<String>> rule, Map<String, Object> held, Map<String, Object> written) {
        Map<String, Object> merged = new HashMap<>(held);
        merged.putAll(written);
        Findings findings = new Findings();
        stage.check(object(rule), access(), merged, written, List.of(), held, findings);
        return !findings.isEmpty();
    }

    @Property
    boolean aCreateIsAcceptedExactlyWhenThePairIsAPairOfTheRule(@ForAll("rules") Map<String, List<String>> rule,
            @ForAll("controlling") String kind, @ForAll("dependent") String area) {
        boolean listed = rule.containsKey(kind) && rule.get(kind).contains(area);

        return refused(rule, Map.of(), Map.of("kind", kind, "area", area)) == !listed;
    }

    @Property
    boolean aDependentValueWithoutAControllingValueIsAlwaysRefused(@ForAll("rules") Map<String, List<String>> rule,
            @ForAll("dependent") String area) {
        return refused(rule, Map.of(), Map.of("area", area));
    }

    @Property
    boolean aRecordHoldingAnyPairIsNeverBlockedByAnEditThatLeavesThePairAlone(
            @ForAll("rules") Map<String, List<String>> rule, @ForAll("controlling") String kind,
            @ForAll("dependent") String area) {
        Map<String, Object> held = Map.of("kind", kind, "area", area, "title", "before");

        return !refused(rule, held, Map.of("title", "after"))
                && !refused(rule, held, Map.of("kind", kind, "area", area, "title", "after"));
    }

    @Property
    boolean changingOnlyTheControllingValueIsJudgedAgainstTheDependentValueHeld(
            @ForAll("rules") Map<String, List<String>> rule, @ForAll("controlling") String before,
            @ForAll("dependent") String area, @ForAll("controlling") String after) {
        Map<String, Object> held = Map.of("kind", before, "area", area);
        boolean listed = rule.containsKey(after) && rule.get(after).contains(area);

        return refused(rule, held, Map.of("kind", after)) == (!before.equals(after) && !listed);
    }

    @Property
    boolean changingOnlyTheDependentValueIsJudgedAgainstTheControllingValueHeld(
            @ForAll("rules") Map<String, List<String>> rule, @ForAll("controlling") String kind,
            @ForAll("dependent") String before, @ForAll("dependent") String after) {
        Map<String, Object> held = Map.of("kind", kind, "area", before);
        boolean listed = rule.containsKey(kind) && rule.get(kind).contains(after);

        return refused(rule, held, Map.of("area", after)) == (!before.equals(after) && !listed);
    }

    @Provide
    Arbitrary<String> controlling() {
        return Arbitraries.of(CONTROLLING);
    }

    @Provide
    Arbitrary<String> dependent() {
        return Arbitraries.of(DEPENDENT);
    }
}
