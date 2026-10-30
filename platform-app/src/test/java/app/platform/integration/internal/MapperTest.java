package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.internal.Mapper.Assignment;
import app.platform.integration.internal.Mapper.Inbound;
import app.platform.integration.internal.Mapper.Outbound;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.MappingFieldSpec;
import app.platformapi.MappingSpec;
import app.platformapi.MappingValuePair;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The mapping compiler and the mapper (Sprint 28, ADR-0216, ADR-0218): every conversion, both ways, the conflict rule,
 * the checks of a definition and the hostile expressions. No database, no container: the engine and the compiler are
 * pure. A value of a record appears here only as a literal of the test.
 */
class MapperTest {

    private static final Instant NOW = Instant.parse("2030-01-31T09:30:00Z");

    private final ExpressionEngine engine = new ExpressionEngine(Limits.standard());
    private final MappingCompiler compiler = new MappingCompiler(engine);
    private final Mapper mapper = new Mapper(engine);

    private static FieldDefinition field(String name, FieldType type, app.platform.metadata.FieldConfiguration config) {
        return new FieldDefinition("Item__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null, config,
                false, 0);
    }

    private static final ObjectDefinition OBJECT = new ObjectDefinition("Item__c", "Item", "Items", "",
            DefinitionKind.CUSTOM, null, true, 0, List.of(
                    field("name__c", FieldType.TEXT, new TextConfiguration(80)),
                    field("price__c", FieldType.DECIMAL, new DecimalConfiguration(12, 4)),
                    field("status__c", FieldType.TEXT, new TextConfiguration(20)),
                    field("qty__c", FieldType.NUMBER, new app.platform.metadata.FieldConfiguration.NumberConfiguration(
                            9)),
                    field("active__c", FieldType.BOOLEAN, new NoConfiguration()),
                    field("due__c", FieldType.DATE, new NoConfiguration()),
                    new FieldDefinition("Item__c", "id", "id", "", DefinitionKind.SYSTEM, FieldType.TEXT, true, true,
                            null, new TextConfiguration(36), false, 0)));

    private static MappingFieldSpec map(String external, String platform, String direction, String type) {
        return new MappingFieldSpec(external, platform, direction, type, false, null, "NONE", List.of(), null, null,
                null);
    }

    private static MappingFieldSpec convert(String external, String platform, String direction, String type,
            String conversion, List<MappingValuePair> pairs, String scale) {
        return new MappingFieldSpec(external, platform, direction, type, false, null, conversion, pairs, scale, null,
                null);
    }

    private static MappingSpec spec(String conflict, String changedAt, MappingFieldSpec... fields) {
        return new MappingSpec(conflict, changedAt, Boolean.TRUE, Boolean.TRUE, List.of(fields));
    }

    private CompiledMapping compile(MappingSpec spec) {
        MappingCompiler.Result result = compiler.compile("item", spec, OBJECT);
        assertThat(result.problems()).isEmpty();
        return result.mapping();
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---- outbound ----

    @Test
    void aRecordBecomesAnEntityWithTheExternalNamesAndTypedValues() {
        CompiledMapping mapping = compile(spec("PLATFORM_WINS", null, map("title", "name__c", "BOTH", "TEXT"),
                map("amount", "price__c", "OUTBOUND", "NUMBER"), map("live", "active__c", "BOTH", "BOOLEAN"),
                map("due", "due__c", "BOTH", "DATE")));

        Outbound out = mapper.out(mapping, values("name__c", "Widget", "price__c", new BigDecimal("12.50"),
                "active__c", Boolean.TRUE, "due__c", LocalDate.of(2030, 2, 1)), null, NOW);

        assertThat(out.failure()).isNull();
        assertThat(out.entity().kind()).isEqualTo("item");
        assertThat(out.entity().externalId()).isNull();
        assertThat(out.entity().fields()).containsEntry("title", "Widget").containsEntry("amount",
                new BigDecimal("12.50")).containsEntry("live", Boolean.TRUE).containsEntry("due",
                LocalDate.of(2030, 2, 1));
    }

    @Test
    void aFieldWithNoValueIsLeftOutAndADefaultFillsIt() {
        MappingFieldSpec defaulted = new MappingFieldSpec("title", "name__c", "OUTBOUND", "TEXT", false, "Untitled",
                "NONE", List.of(), null, null, null);
        CompiledMapping mapping = compile(spec("PLATFORM_WINS", null, defaulted, map("live", "active__c", "OUTBOUND",
                "BOOLEAN")));

        Outbound out = mapper.out(mapping, values(), null, NOW);

        assertThat(out.entity().fields()).containsOnlyKeys("title").containsEntry("title", "Untitled");
    }

    @Test
    void aRequiredFieldWithNoValueStopsTheSendWithAStableReasonAndNoValue() {
        MappingFieldSpec required = new MappingFieldSpec("title", "name__c", "OUTBOUND", "TEXT", true, null, "NONE",
                List.of(), null, null, null);
        CompiledMapping mapping = compile(spec("PLATFORM_WINS", null, required));

        Outbound out = mapper.out(mapping, values(), null, NOW);

        assertThat(out.entity()).isNull();
        assertThat(out.failure().reason()).isEqualTo("required_missing");
    }

    @Test
    void anOutgoingExpressionComputesTheValueFromTheRecord() {
        MappingFieldSpec shouted = new MappingFieldSpec("title", "name__c", "OUTBOUND", "TEXT", false, null, "NONE",
                List.of(), null, "UPPER(name__c)", null);
        CompiledMapping mapping = compile(spec("PLATFORM_WINS", null, shouted));

        Outbound out = mapper.out(mapping, values("name__c", "widget"), null, NOW);

        assertThat(out.entity().fields()).containsEntry("title", "WIDGET");
    }

    // ---- inbound ----

    @Test
    void anIncomingEntityBecomesAssignmentsWrittenAsFieldDefaultsAre() {
        CompiledMapping mapping = compile(spec("EXTERNAL_WINS", null, map("title", "name__c", "BOTH", "TEXT"),
                map("amount", "price__c", "BOTH", "NUMBER"), map("live", "active__c", "BOTH", "BOOLEAN"),
                map("due", "due__c", "BOTH", "DATE")));
        CanonicalEntity incoming = new CanonicalEntity("item", "x-1", Map.of("title", "Gadget", "amount",
                new BigDecimal("3.5"), "live", Boolean.FALSE, "due", "2030-03-04"));

        Inbound in = mapper.in(mapping, incoming, null, NOW);

        assertThat(in.failure()).isNull();
        assertThat(in.assignments()).extracting(Assignment::platform, Assignment::text).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("name__c", "Gadget"),
                org.assertj.core.groups.Tuple.tuple("price__c", "3.5"),
                org.assertj.core.groups.Tuple.tuple("active__c", "false"),
                org.assertj.core.groups.Tuple.tuple("due__c", "2030-03-04"));
    }

    @Test
    void aValueOfTheWrongTypeStopsTheChangeWithAStableReason() {
        CompiledMapping mapping = compile(spec("EXTERNAL_WINS", null, map("amount", "price__c", "INBOUND", "NUMBER")));

        Inbound in = mapper.in(mapping, new CanonicalEntity("item", "x-1", Map.of("amount", "not a number")), null,
                NOW);

        assertThat(in.failure().reason()).isEqualTo("value_invalid");
        assertThat(in.assignments()).isEmpty();
    }

    @Test
    void aFieldTheChangeDoesNotMentionIsLeftAloneAndRequiredOnlyBindsACreate() {
        MappingFieldSpec required = new MappingFieldSpec("title", "name__c", "BOTH", "TEXT", true, null, "NONE",
                List.of(), null, null, null);
        CompiledMapping mapping = compile(spec("EXTERNAL_WINS", null, required, map("amount", "price__c", "BOTH",
                "NUMBER")));
        CanonicalEntity partial = new CanonicalEntity("item", "x-1", Map.of("amount", new BigDecimal("1")));

        assertThat(mapper.in(mapping, partial, null, NOW).failure().reason()).isEqualTo("required_missing");
        Inbound update = mapper.in(mapping, partial, NOW.minusSeconds(60), NOW);
        assertThat(update.failure()).isNull();
        assertThat(update.assignments()).extracting(Assignment::platform).containsExactly("price__c");
    }

    // ---- the conflict rule ----

    private Inbound conflict(String rule, Instant recordUpdated, String externalChanged) {
        CompiledMapping mapping = compile(spec(rule, "changed_at", map("title", "name__c", "BOTH", "TEXT"),
                map("note", "status__c", "INBOUND", "TEXT")));
        Map<String, Object> fields = new HashMap<>(Map.of("title", "Outside", "note", "Always"));
        if (externalChanged != null) {
            fields.put("changed_at", externalChanged);
        }
        return mapper.in(mapping, new CanonicalEntity("item", "x-1", fields), recordUpdated, NOW);
    }

    @Test
    void platformWinsKeepsTheBothWayFieldsButStillWritesTheFieldsThatOnlyComeIn() {
        Inbound in = conflict("PLATFORM_WINS", NOW.minusSeconds(3600), "2030-01-31T09:00:00Z");

        assertThat(in.assignments()).extracting(Assignment::platform).containsExactly("status__c");
        assertThat(in.skipped()).extracting(Mapper.Skipped::field, Mapper.Skipped::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("name__c", "platform_newer"));
    }

    @Test
    void externalWinsWritesEveryField() {
        Inbound in = conflict("EXTERNAL_WINS", NOW, null);

        assertThat(in.assignments()).extracting(Assignment::platform).containsExactlyInAnyOrder("name__c",
                "status__c");
        assertThat(in.skipped()).isEmpty();
    }

    @Test
    void theNewerSideWinsUnderThePlatformUnlessNewerRule() {
        Instant recordChanged = Instant.parse("2030-01-31T08:00:00Z");

        Inbound newer = conflict("PLATFORM_UNLESS_NEWER", recordChanged, "2030-01-31T09:00:00Z");
        Inbound older = conflict("PLATFORM_UNLESS_NEWER", recordChanged, "2030-01-31T07:00:00Z");
        Inbound unknown = conflict("PLATFORM_UNLESS_NEWER", recordChanged, null);

        assertThat(newer.assignments()).extracting(Assignment::platform).containsExactlyInAnyOrder("name__c",
                "status__c");
        assertThat(older.assignments()).extracting(Assignment::platform).containsExactly("status__c");
        assertThat(unknown.assignments()).extracting(Assignment::platform).containsExactly("status__c");
    }

    @Test
    void theRuleDoesNotApplyToANewRecord() {
        Inbound in = conflict("PLATFORM_WINS", null, null);

        assertThat(in.assignments()).extracting(Assignment::platform).containsExactlyInAnyOrder("name__c",
                "status__c");
    }

    // ---- conversions ----

    @Test
    void aValueMapIsFollowedBothWaysAndAnUnmappedValueStopsTheWork() {
        MappingFieldSpec map = convert("state", "status__c", "BOTH", "TEXT", "VALUE_MAP", List.of(
                new MappingValuePair("OPEN", "o"), new MappingValuePair("CLOSED", "c")), null);
        CompiledMapping mapping = compile(spec("EXTERNAL_WINS", null, map));

        assertThat(mapper.out(mapping, values("status__c", "CLOSED"), null, NOW).entity().fields())
                .containsEntry("state", "c");
        Inbound in = mapper.in(mapping, new CanonicalEntity("item", "x", Map.of("state", "o")), null, NOW);
        assertThat(in.assignments()).extracting(Assignment::text).containsExactly("OPEN");
        assertThat(mapper.out(mapping, values("status__c", "OTHER"), null, NOW).failure().reason())
                .isEqualTo("value_not_mapped");
        assertThat(mapper.in(mapping, new CanonicalEntity("item", "x", Map.of("state", "z")), null, NOW)
                .failure().reason()).isEqualTo("value_not_mapped");
    }

    @Test
    void aNumberScaleGivesBackWhatWentOutForManyNumbers() {
        MappingFieldSpec cents = convert("cents", "price__c", "BOTH", "NUMBER", "NUMBER_SCALE", List.of(), "100");
        CompiledMapping mapping = compile(spec("EXTERNAL_WINS", null, cents));
        Random random = new Random(28);

        for (int i = 0; i < 500; i++) {
            BigDecimal original = new BigDecimal(random.nextLong(-1_000_000_000L, 1_000_000_000L)).movePointLeft(
                    random.nextInt(5));
            Outbound out = mapper.out(mapping, values("price__c", original), null, NOW);
            Inbound back = mapper.in(mapping, out.entity(), null, NOW);

            assertThat(new BigDecimal(back.assignments().getFirst().text())).isEqualByComparingTo(original);
        }
    }

    @Test
    void trimmingEmptiesATextThatIsOnlySpaces() {
        MappingFieldSpec trim = convert("title", "name__c", "OUTBOUND", "TEXT", "TRIM", List.of(), null);
        CompiledMapping mapping = compile(spec("PLATFORM_WINS", null, trim));

        assertThat(mapper.out(mapping, values("name__c", "  padded  "), null, NOW).entity().fields())
                .containsEntry("title", "padded");
        assertThat(mapper.out(mapping, values("name__c", "   "), null, NOW).entity().fields()).isEmpty();
    }

    @Test
    void theSameInputGivesTheSameFingerprintWhateverTheOrder() {
        Map<String, Object> first = new java.util.LinkedHashMap<>();
        first.put("a", "1");
        first.put("b", new BigDecimal("2.0"));
        Map<String, Object> second = new java.util.LinkedHashMap<>();
        second.put("b", new BigDecimal("2.00"));
        second.put("a", "1");

        assertThat(Mapper.hash(first)).isEqualTo(Mapper.hash(second)).hasSize(64);
        assertThat(Mapper.hash(first)).isNotEqualTo(Mapper.hash(Map.of("a", "2")));
    }

    // ---- the checks of a definition ----

    private Map<String, List<String>> problems(MappingSpec spec) {
        return compiler.compile("item", spec, OBJECT).problems();
    }

    @Test
    void aDefinitionThatDoesNotFitTheObjectIsRefusedWithFixedWordsAtAPlace() {
        Map<String, List<String>> problems = problems(spec("PLATFORM_WINS", null,
                map("title", "missing__c", "BOTH", "TEXT"), map("title", "name__c", "BOTH", "TEXT"),
                map("amount", "price__c", "BOTH", "TEXT"), map("sys", "id", "BOTH", "TEXT")));

        assertThat(problems).containsKeys("fields[0].platform", "fields[1].external", "fields[2].conversion",
                "fields[3].direction");
        assertThat(problems.toString()).doesNotContain("missing__c");
    }

    @Test
    void aBothWayFieldNeedsAnExpressionForEachWayOrNone() {
        MappingFieldSpec one = new MappingFieldSpec("title", "name__c", "BOTH", "TEXT", false, null, "NONE",
                List.of(), null, "UPPER(name__c)", null);

        assertThat(problems(spec("EXTERNAL_WINS", null, one))).containsKey("fields[0].conversion");
    }

    @Test
    void theRuleThatNeedsATimeSaysSoWhenNoFieldNamesOne() {
        assertThat(problems(spec("PLATFORM_UNLESS_NEWER", null, map("title", "name__c", "BOTH", "TEXT"))))
                .containsKey("changedAtField");
        assertThat(problems(spec("NO_SUCH_RULE", null, map("title", "name__c", "BOTH", "TEXT"))))
                .containsKey("conflict");
    }

    @Test
    void aValueMapMustBeFollowableBothWaysAndAScaleMustBeInRange() {
        MappingFieldSpec twice = convert("state", "status__c", "BOTH", "TEXT", "VALUE_MAP", List.of(
                new MappingValuePair("A", "x"), new MappingValuePair("B", "x")), null);
        MappingFieldSpec huge = convert("cents", "price__c", "BOTH", "NUMBER", "NUMBER_SCALE", List.of(), "0");

        Map<String, List<String>> problems = problems(spec("EXTERNAL_WINS", null, twice, huge));

        assertThat(problems).containsKeys("fields[0].valueMap", "fields[1].scale");
    }

    @Test
    void anExpressionThatReadsTheClockOrAnUnknownFieldOrHasTheWrongTypeIsRefused() {
        for (String text : List.of("NOW()", "TODAY()", "unknown_field", "1 + 1", "SLEEP(5)", "name__c +", "")) {
            MappingFieldSpec hostile = new MappingFieldSpec("title", "name__c", "OUTBOUND", "TEXT", false, null,
                    "NONE", List.of(), null, text, null);
            Map<String, List<String>> problems = problems(spec("PLATFORM_WINS", null, hostile));

            if (text.isEmpty()) {
                assertThat(problems).isEmpty();
            } else {
                assertThat(problems).as(text).containsKey("fields[0].outExpression");
            }
        }
    }

    @Test
    void anExpressionThatCannotBeWorkedOutStopsTheRecordNotTheServer() {
        MappingFieldSpec divide = new MappingFieldSpec("amount", "price__c", "OUTBOUND", "NUMBER", false, null,
                "NONE", List.of(), null, "price__c / 0", null);
        CompiledMapping mapping = compile(spec("PLATFORM_WINS", null, divide));

        Outbound out = mapper.out(mapping, values("price__c", BigDecimal.ONE), null, NOW);

        assertThat(out.failure().reason()).isEqualTo("transform_failed");
    }

    @Test
    void nothingTheMapperHoldsPrintsAValue() {
        CompiledMapping mapping = compile(spec("PLATFORM_WINS", null, map("title", "name__c", "BOTH", "TEXT")));
        Outbound out = mapper.out(mapping, values("name__c", "canary-value"), null, NOW);
        Inbound in = mapper.in(mapping, new CanonicalEntity("item", "canary-id", Map.of("title", "canary-value")),
                null, NOW);

        assertThat(out.entity().toString()).doesNotContain("canary");
        assertThat(in.assignments().toString()).doesNotContain("canary");
        assertThat(mapping.toString()).doesNotContain("title").doesNotContain("name__c");
    }
}
