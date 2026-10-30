package app.platform.integration.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionType;
import app.platform.expression.Purpose;
import app.platform.integration.internal.CompiledMapping.Conflict;
import app.platform.integration.internal.CompiledMapping.Conversion;
import app.platform.integration.internal.CompiledMapping.Direction;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.MappingFieldSpec;
import app.platformapi.MappingSpec;
import app.platformapi.MappingValuePair;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks a mapping definition against the object it is for and makes it ready to run (Sprint 28, ADR-0218). Pure: no
 * database, no clock, no I/O; the fields of the object are handed in. Every problem is a fixed sentence at a place
 * ({@code fields[2].platform}); a sentence never repeats what the person typed, not even a name, so a definition cannot
 * leak through its own refusal. Expressions are checked by the expression engine under the purpose
 * {@link Purpose#MAPPING_TRANSFORM}: no clock, bounded, never run as code.
 */
@Component
class MappingCompiler {

    /** The outcome: the ready mapping, or the problems by place. */
    record Result(CompiledMapping mapping, Map<String, List<String>> problems) {

        Result {
            problems = Map.copyOf(problems);
        }

        boolean ok() {
            return problems.isEmpty();
        }
    }

    static final Pattern EXTERNAL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    static final Pattern ENTITY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final BigDecimal MAX_FACTOR = new BigDecimal("1000000000");
    private static final Set<FieldType> NEVER_MAPPED = Set.of(FieldType.MULTI_PICKLIST);
    private static final Set<FieldType> NEVER_WRITTEN = Set.of(FieldType.FORMULA, FieldType.AUTO_NUMBER,
            FieldType.LOOKUP, FieldType.MASTER_DETAIL, FieldType.MULTI_PICKLIST);

    private final ExpressionEngine engine;

    MappingCompiler(ExpressionEngine engine) {
        this.engine = engine;
    }

    ExpressionEngine engine() {
        return engine;
    }

    /** What an expression sees in a field: a formula has the type it is declared to give. */
    static ExpressionType typeOf(FieldDefinition field) {
        if (field.configuration() instanceof FormulaConfiguration formula) {
            return typeOf(formula.resultType());
        }
        return typeOf(field.type());
    }

    static ExpressionType typeOf(FieldType type) {
        return switch (type) {
            case NUMBER, DECIMAL, CURRENCY, PERCENT -> ExpressionType.NUMBER;
            case BOOLEAN -> ExpressionType.BOOLEAN;
            case DATE -> ExpressionType.DATE;
            case DATETIME -> ExpressionType.DATETIME;
            case TIME -> ExpressionType.TIME;
            case TEXT, LONG_TEXT, EMAIL, PHONE, URL, PICKLIST, MULTI_PICKLIST, LOOKUP, MASTER_DETAIL, AUTO_NUMBER,
                    FORMULA -> ExpressionType.TEXT;
        };
    }

    /** Every field of the object by API name, as the expression engine sees it. */
    static Map<String, ExpressionType> schemaOf(ObjectDefinition object) {
        Map<String, ExpressionType> schema = new LinkedHashMap<>();
        for (FieldDefinition field : object.fields()) {
            if (!NEVER_MAPPED.contains(field.type())) {
                schema.put(field.apiName(), typeOf(field));
            }
        }
        return schema;
    }

    /**
     * Checks the definition and makes it ready.
     *
     * @param entity the kind of entity on the external side
     * @param spec the definition
     * @param object the object the mapping is for, as it is now
     */
    Result compile(String entity, MappingSpec spec, ObjectDefinition object) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        if (entity == null || !ENTITY.matcher(entity).matches()) {
            problems.put("entity",
                    List.of("Use 1 to 40 lower case letters, digits and dashes, starting with a letter."));
        }
        Conflict conflict = enumOf(Conflict.class, spec.conflict());
        if (conflict == null) {
            problems.put("conflict", List.of("Choose one of the offered rules."));
        }
        if (spec.changedAtField() != null && !spec.changedAtField().isEmpty()
                && !EXTERNAL_NAME.matcher(spec.changedAtField()).matches()) {
            problems.put("changedAtField", List.of("Use letters, digits and underscores, starting with a letter."));
        }
        List<MappingFieldSpec> given = spec.fields();
        if (given.isEmpty()) {
            problems.put("fields", List.of("Map at least one field."));
        }
        Map<String, ExpressionType> platformSchema = schemaOf(object);
        Map<String, ExpressionType> externalSchema = externalSchema(given, problems);
        List<CompiledMapping.Field> fields = new ArrayList<>();
        Set<String> externals = new HashSet<>();
        Set<String> platforms = new HashSet<>();
        boolean anyBoth = false;
        for (int index = 0; index < given.size(); index++) {
            MappingFieldSpec field = given.get(index);
            String at = "fields[" + index + "]";
            if (!externals.add(field.external())) {
                add(problems, at + ".external", "This external field is mapped twice.");
            }
            if (!platforms.add(field.platform())) {
                add(problems, at + ".platform", "This field is mapped twice.");
            }
            CompiledMapping.Field ready = field(at, field, object, platformSchema, externalSchema, problems);
            if (ready != null) {
                fields.add(ready);
                anyBoth |= ready.direction() == Direction.BOTH;
            }
        }
        if (conflict == Conflict.PLATFORM_UNLESS_NEWER && anyBoth
                && (spec.changedAtField() == null || spec.changedAtField().isEmpty())) {
            problems.put("changedAtField", List.of("Name the field that says when the entity last changed, or "
                    + "choose another rule."));
        }
        if (!problems.isEmpty()) {
            return new Result(null, problems);
        }
        String changed = spec.changedAtField() == null || spec.changedAtField().isEmpty() ? null
                : spec.changedAtField();
        return new Result(new CompiledMapping(entity, conflict, changed, Boolean.TRUE.equals(spec.inboundCreate()),
                Boolean.TRUE.equals(spec.inboundUpdate()), fields), Map.of());
    }

    /** The external fields that can be read by an inbound expression, with the type each carries. */
    private Map<String, ExpressionType> externalSchema(List<MappingFieldSpec> given,
            Map<String, List<String>> problems) {
        Map<String, ExpressionType> schema = new HashMap<>();
        for (MappingFieldSpec field : given) {
            ExpressionType type = enumOf(ExpressionType.class, field.externalType());
            Direction direction = enumOf(Direction.class, field.direction());
            if (type != null && type != ExpressionType.NONE && direction != null && direction.in()
                    && EXTERNAL_NAME.matcher(field.external()).matches()) {
                schema.put(field.external(), type);
            }
        }
        return schema;
    }

    private CompiledMapping.Field field(String at, MappingFieldSpec spec, ObjectDefinition object,
            Map<String, ExpressionType> platformSchema, Map<String, ExpressionType> externalSchema,
            Map<String, List<String>> problems) {
        int before = count(problems);
        if (!EXTERNAL_NAME.matcher(spec.external()).matches()) {
            add(problems, at + ".external", "Use letters, digits and underscores, starting with a letter.");
        }
        Direction direction = enumOf(Direction.class, spec.direction());
        if (direction == null) {
            add(problems, at + ".direction", "Choose out, in or both.");
        }
        ExpressionType externalType = enumOf(ExpressionType.class, spec.externalType());
        if (externalType == null || externalType == ExpressionType.NONE) {
            add(problems, at + ".externalType", "Choose what the external side carries.");
            externalType = null;
        }
        Conversion conversion = enumOf(Conversion.class, spec.conversion());
        if (conversion == null) {
            add(problems, at + ".conversion", "Choose one of the offered conversions.");
        }
        FieldDefinition platform = object.field(spec.platform()).orElse(null);
        if (platform == null || platform.retired() || NEVER_MAPPED.contains(platform.type())) {
            add(problems, at + ".platform", "This object has no such field that can be mapped.");
            platform = null;
        }
        if (direction == null || externalType == null || conversion == null || platform == null) {
            return null;
        }
        if (direction.in() && (platform.kind() == DefinitionKind.SYSTEM || platform.platformWritten()
                || NEVER_WRITTEN.contains(platform.type()))) {
            add(problems, at + ".direction", "A change from outside cannot be written to this field.");
        }
        ExpressionType platformType = typeOf(platform);
        Compiled out = expression(at + ".outExpression", spec.outExpression(), direction.out(), platformSchema,
                externalType, problems, "A value cannot be sent for a field that only comes in.");
        Compiled in = expression(at + ".inExpression", spec.inExpression(), direction.in(), externalSchema,
                platformType, problems, "A value cannot be stored for a field that only goes out.");
        if (direction == Direction.BOTH && (out == null) != (in == null)) {
            add(problems, at + ".conversion", "A field that goes both ways needs an expression for each way, or "
                    + "none.");
        }
        Map<String, String> toExternal = new LinkedHashMap<>();
        Map<String, String> toPlatform = new LinkedHashMap<>();
        BigDecimal factor = null;
        boolean needsConversion = direction.out() && out == null || direction.in() && in == null;
        if (needsConversion) {
            factor = conversion(at, spec, conversion, platformType, externalType, toExternal, toPlatform, problems);
        }
        if (spec.defaultValue() != null && !spec.defaultValue().isEmpty()) {
            defaultProblem(at, spec.defaultValue(), direction, externalType, platformType, problems);
        }
        if (count(problems) > before) {
            return null;
        }
        return new CompiledMapping.Field(spec.external(), spec.platform(), direction, externalType, platformType,
                Boolean.TRUE.equals(spec.required()),
                spec.defaultValue() == null || spec.defaultValue().isEmpty() ? null : spec.defaultValue(), conversion,
                toExternal, toPlatform, factor, out, in);
    }

    private Compiled expression(String at, String text, boolean allowed, Map<String, ExpressionType> schema,
            ExpressionType expected, Map<String, List<String>> problems, String notAllowed) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (!allowed) {
            add(problems, at, notAllowed);
            return null;
        }
        try {
            return engine.compile(text, schema, Purpose.MAPPING_TRANSFORM, expected);
        } catch (ExpressionException invalid) {
            // The engine's sentences carry a place and plain words, never the typed text (ADR-0076).
            add(problems, at, invalid.problem());
            return null;
        }
    }

    private BigDecimal conversion(String at, MappingFieldSpec spec, Conversion conversion,
            ExpressionType platformType, ExpressionType externalType, Map<String, String> toExternal,
            Map<String, String> toPlatform, Map<String, List<String>> problems) {
        switch (conversion) {
            case NONE -> {
                if (platformType != externalType) {
                    add(problems, at + ".conversion", "The types differ. Use a conversion or an expression.");
                }
            }
            case TRIM -> {
                if (platformType != ExpressionType.TEXT || externalType != ExpressionType.TEXT) {
                    add(problems, at + ".conversion", "Trimming works on text on both sides.");
                }
            }
            case VALUE_MAP -> valueMap(at, spec, platformType, externalType, toExternal, toPlatform, problems);
            case NUMBER_SCALE -> {
                return factor(at, spec, platformType, externalType, problems);
            }
            default -> add(problems, at + ".conversion", "Choose one of the offered conversions.");
        }
        return null;
    }

    private static void valueMap(String at, MappingFieldSpec spec, ExpressionType platformType,
            ExpressionType externalType, Map<String, String> toExternal, Map<String, String> toPlatform,
            Map<String, List<String>> problems) {
        if (platformType != ExpressionType.TEXT || externalType != ExpressionType.TEXT) {
            add(problems, at + ".conversion", "A value map works on text on both sides.");
            return;
        }
        if (spec.valueMap().isEmpty()) {
            add(problems, at + ".valueMap", "List at least one pair.");
            return;
        }
        for (MappingValuePair pair : spec.valueMap()) {
            if (pair.platform().isEmpty() || pair.external().isEmpty()) {
                add(problems, at + ".valueMap", "A value cannot be empty.");
                return;
            }
            if (toExternal.put(pair.platform(), pair.external()) != null
                    || toPlatform.put(pair.external(), pair.platform()) != null) {
                add(problems, at + ".valueMap", "Each value may appear in one pair only, so that the map can be "
                        + "followed both ways.");
                return;
            }
        }
    }

    private static BigDecimal factor(String at, MappingFieldSpec spec, ExpressionType platformType,
            ExpressionType externalType, Map<String, List<String>> problems) {
        if (platformType != ExpressionType.NUMBER || externalType != ExpressionType.NUMBER) {
            add(problems, at + ".conversion", "A scale works on numbers on both sides.");
            return null;
        }
        BigDecimal factor;
        try {
            factor = new BigDecimal(spec.scale() == null ? "" : spec.scale().strip());
        } catch (NumberFormatException invalid) {
            add(problems, at + ".scale", "Write the factor in plain digits, for example 100 or 0.01.");
            return null;
        }
        if (factor.signum() <= 0 || factor.compareTo(MAX_FACTOR) > 0 || factor.scale() > 6) {
            add(problems, at + ".scale", "Use a factor above zero, up to 1000000000, with at most 6 decimals.");
            return null;
        }
        return factor;
    }

    private void defaultProblem(String at, String text, Direction direction, ExpressionType externalType,
            ExpressionType platformType, Map<String, List<String>> problems) {
        try {
            if (direction.out()) {
                engine.parseValue(externalType, text, "default");
            }
            if (direction.in()) {
                engine.parseValue(platformType, text, "default");
            }
        } catch (ExpressionException invalid) {
            add(problems, at + ".defaultValue", "The default does not fit the type of the field.");
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String key) {
        if (key == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, key.toUpperCase(Locale.ROOT).equals(key) ? key : "?");
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static void add(Map<String, List<String>> problems, String place, String sentence) {
        problems.computeIfAbsent(place, key -> new ArrayList<>()).add(sentence);
    }

    private static int count(Map<String, List<String>> problems) {
        return problems.values().stream().mapToInt(List::size).sum();
    }
}
