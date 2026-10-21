package app.platform.metadata.internal;

import app.platform.metadata.DeleteBehaviour;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldType;
import app.platform.metadata.PicklistValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored form of a field's configuration: a small JSON object whose keys are the names of the settings (ADR-0060).
 * The form is written by this class only and read by this class only; the stored value is always one this code wrote,
 * checked by {@link FieldRules} first, so reading does not re-validate, it only rebuilds. A key a later release
 * does not know is ignored, and a missing key takes the type's default, which keeps an old row readable by a newer
 * release (expand and contract, ADR-0009).
 */
final class ConfigCodec {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper json = JsonMapper.builder().build();

    /** The JSON text to store. */
    String write(FieldConfiguration configuration) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (configuration) {
            case TextConfiguration text -> out.put("maxLength", text.maxLength());
            case NumberConfiguration number -> out.put("digits", number.digits());
            case DecimalConfiguration decimal -> {
                out.put("precision", decimal.precision());
                out.put("scale", decimal.scale());
            }
            case NoConfiguration none -> {
                // nothing to store
            }
            case PicklistConfiguration picklist -> {
                List<Map<String, Object>> values = new ArrayList<>();
                for (PicklistValue value : picklist.values()) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("value", value.value());
                    one.put("label", value.label());
                    one.put("active", value.active());
                    values.add(one);
                }
                out.put("values", values);
                if (picklist.dependency() != null) {
                    out.put("dependency", dependency(picklist.dependency()));
                }
            }
            case ReferenceConfiguration reference -> {
                out.put("targetObject", reference.targetObject());
                out.put("onDelete", reference.onDelete().name());
                out.put("reparentable", reference.reparentable());
                out.put("listLabel", reference.listLabel());
            }
            case FormulaConfiguration formula -> {
                out.put("expression", formula.expression());
                out.put("resultType", formula.resultType().name());
                out.put("verified", formula.verified());
            }
            case AutoNumberConfiguration auto -> {
                out.put("prefix", auto.prefix());
                out.put("startAt", auto.startAt());
                out.put("width", auto.width());
            }
        }
        return json.writeValueAsString(out);
    }

    /** The configuration a stored text describes for a field of the type. */
    FieldConfiguration read(FieldType type, String stored) {
        Map<String, Object> in = stored == null || stored.isBlank() ? Map.of() : json.readValue(stored, MAP);
        return switch (type) {
            case TEXT -> new TextConfiguration(integer(in, "maxLength", FieldRules.TEXT_DEFAULT));
            case LONG_TEXT -> new TextConfiguration(integer(in, "maxLength", FieldRules.LONG_TEXT_DEFAULT));
            case EMAIL -> new TextConfiguration(FieldRules.EMAIL_LENGTH);
            case PHONE -> new TextConfiguration(FieldRules.PHONE_LENGTH);
            case URL -> new TextConfiguration(FieldRules.URL_LENGTH);
            case NUMBER -> new NumberConfiguration(integer(in, "digits", FieldRules.NUMBER_DIGITS));
            case DECIMAL, CURRENCY, PERCENT -> new DecimalConfiguration(integer(in, "precision", 18),
                    integer(in, "scale", 2));
            case BOOLEAN, DATE, DATETIME, TIME -> new NoConfiguration();
            case PICKLIST -> new PicklistConfiguration(picklist(in), dependency(in));
            case MULTI_PICKLIST -> new PicklistConfiguration(picklist(in));
            case LOOKUP, MASTER_DETAIL -> reference(type, in);
            case FORMULA -> new FormulaConfiguration(text(in, "expression", ""),
                    FieldType.fromCode(text(in, "resultType", "TEXT")).orElse(FieldType.TEXT),
                    Boolean.TRUE.equals(in.get("verified")));
            case AUTO_NUMBER -> new AutoNumberConfiguration(text(in, "prefix", ""), longNumber(in, "startAt", 1),
                    integer(in, "width", FieldRules.AUTO_NUMBER_WIDTH_DEFAULT));
        };
    }

    /** A row written before Sprint 11 has only the target: the behaviour the type has by default fills the rest. */
    private static ReferenceConfiguration reference(FieldType type, Map<String, Object> in) {
        ReferenceConfiguration defaults = ReferenceConfiguration.defaultsFor(type, text(in, "targetObject", ""));
        DeleteBehaviour onDelete = type == FieldType.MASTER_DETAIL ? DeleteBehaviour.CASCADE
                : Arrays.stream(DeleteBehaviour.values())
                        .filter(behaviour -> behaviour.name().equals(in.get("onDelete")))
                        .findFirst().orElse(defaults.onDelete());
        return new ReferenceConfiguration(defaults.targetObject(), onDelete,
                Boolean.TRUE.equals(in.get("reparentable")), text(in, "listLabel", ""));
    }

    private static List<PicklistValue> picklist(Map<String, Object> in) {
        List<PicklistValue> values = new ArrayList<>();
        if (in.get("values") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> one && one.get("value") instanceof String value) {
                    Object label = one.get("label");
                    values.add(new PicklistValue(value, label instanceof String text ? text : value,
                            !Boolean.FALSE.equals(one.get("active"))));
                }
            }
        }
        return values;
    }

    /** The rule as stored: the keys sorted, so that the same rule always gives the same text. */
    private static Map<String, Object> dependency(Dependency dependency) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controllingField", dependency.controllingField());
        Map<String, List<String>> allowed = new LinkedHashMap<>();
        new TreeMap<>(dependency.allowed()).forEach((key, values) -> allowed.put(key, values));
        out.put("allowed", allowed);
        return out;
    }

    private static Dependency dependency(Map<String, Object> in) {
        if (!(in.get("dependency") instanceof Map<?, ?> stored)
                || !(stored.get("controllingField") instanceof String field)) {
            return null;
        }
        Map<String, List<String>> allowed = new LinkedHashMap<>();
        if (stored.get("allowed") instanceof Map<?, ?> rows) {
            rows.forEach((key, list) -> {
                List<String> values = new ArrayList<>();
                if (list instanceof List<?> items) {
                    items.forEach(item -> values.add(String.valueOf(item)));
                }
                allowed.put(String.valueOf(key), values);
            });
        }
        return new Dependency(field, allowed);
    }

    private static int integer(Map<String, Object> in, String key, int fallback) {
        return in.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    private static long longNumber(Map<String, Object> in, String key, long fallback) {
        return in.get(key) instanceof Number number ? number.longValue() : fallback;
    }

    private static String text(Map<String, Object> in, String key, String fallback) {
        return in.get(key) instanceof String text ? text : fallback;
    }
}
