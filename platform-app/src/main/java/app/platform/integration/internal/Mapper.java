package app.platform.integration.internal;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionType;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.internal.CompiledMapping.Conflict;
import app.platform.integration.internal.CompiledMapping.Direction;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Turns a record into a canonical entity and a canonical entity into the changes of a record (Sprint 28, ADR-0216,
 * ADR-0218). Pure: it reads the values it is given and the compiled mapping, calls the expression engine for the
 * transformations and nothing else (no database, no clock, no I/O), so the same input always gives the same output,
 * which is what lets a mapping be tried on a sample and replayed.
 *
 * <p>It decides nothing about who may read or write: the caller has already asked the permission, the scope and the
 * lock. It never logs, and a failure carries a stable reason, never a value.
 */
@Component
class Mapper {

    /** A reason a mapping could not be carried out, stable and without any value. */
    record Failure(String reason) {

        static Failure of(FlowReason reason) {
            return new Failure(reason.key());
        }
    }

    /** A field left out and why. */
    record Skipped(String field, String reason) {
    }

    /**
     * What a record becomes on its way out.
     *
     * @param entity the entity to send, or null when there is a failure
     * @param failure why it cannot be sent, or null
     */
    record Outbound(CanonicalEntity entity, Failure failure) {
    }

    /** One field to store, with the value written as a field default is written. */
    record Assignment(String platform, String text) {

        @Override
        public String toString() {
            return "Assignment[redacted]";
        }
    }

    /**
     * What a change from outside becomes.
     *
     * @param assignments the fields to store
     * @param skipped the fields left out
     * @param failure why the change cannot be applied, or null
     */
    record Inbound(List<Assignment> assignments, List<Skipped> skipped, Failure failure) {

        Inbound {
            assignments = List.copyOf(assignments);
            skipped = List.copyOf(skipped);
        }

        static Inbound failed(FlowReason reason) {
            return new Inbound(List.of(), List.of(), Failure.of(reason));
        }
    }

    private static final int DIVISION_DIGITS = 30;

    private final ExpressionEngine engine;

    Mapper(ExpressionEngine engine) {
        this.engine = engine;
    }

    /**
     * The entity a record becomes.
     *
     * @param values the values of the record by field API name, as the engine takes them
     * @param externalId the identifier on the external side, or null for a create
     */
    Outbound out(CompiledMapping mapping, Map<String, Object> values, String externalId, Instant now) {
        Map<String, Object> fields = new HashMap<>();
        for (CompiledMapping.Field field : mapping.fields()) {
            if (!field.direction().out()) {
                continue;
            }
            Object value;
            try {
                value = field.out() != null ? engine.evaluate(field.out(), values, now) : values.get(field.platform());
                if (value == null && field.defaultText() != null) {
                    value = engine.parseValue(field.externalType(), field.defaultText(), field.external());
                }
                if (value == null) {
                    if (field.required()) {
                        return new Outbound(null, Failure.of(FlowReason.REQUIRED_MISSING));
                    }
                    continue;
                }
                if (field.out() == null) {
                    value = convertOut(field, value);
                    if (value == null) {
                        if (field.conversion() == CompiledMapping.Conversion.VALUE_MAP) {
                            return new Outbound(null, Failure.of(FlowReason.VALUE_NOT_MAPPED));
                        }
                        if (field.required()) {
                            return new Outbound(null, Failure.of(FlowReason.REQUIRED_MISSING));
                        }
                        continue;
                    }
                }
            } catch (ExpressionException failed) {
                return new Outbound(null, Failure.of(FlowReason.TRANSFORM_FAILED));
            }
            fields.put(field.external(), value);
        }
        return new Outbound(new CanonicalEntity(mapping.entity(), externalId, fields), null);
    }

    /**
     * The changes a change from outside makes.
     *
     * @param recordUpdatedAt when the record was last changed, or null when the change would make a new record
     */
    Inbound in(CompiledMapping mapping, CanonicalEntity entity, Instant recordUpdatedAt, Instant now) {
        boolean update = recordUpdatedAt != null;
        Map<String, Object> typed = new HashMap<>();
        for (CompiledMapping.Field field : mapping.fields()) {
            Object raw = entity.fields().get(field.external());
            if (!field.direction().in() || raw == null) {
                continue;
            }
            Object value = typed(field.externalType(), raw, field.external());
            if (value == null) {
                return Inbound.failed(FlowReason.VALUE_INVALID);
            }
            typed.put(field.external(), value);
        }
        Instant changedAt = externalChangedAt(mapping, entity);
        boolean externalNewer = changedAt != null && update && changedAt.isAfter(recordUpdatedAt);
        List<Assignment> assignments = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (CompiledMapping.Field field : mapping.fields()) {
            if (!field.direction().in()) {
                continue;
            }
            if (update && field.direction() == Direction.BOTH && !wins(mapping.conflict(), externalNewer)) {
                skipped.add(new Skipped(field.platform(), FlowReason.PLATFORM_NEWER.key()));
                continue;
            }
            try {
                Object value = field.in() != null ? engine.evaluate(field.in(), typed, now)
                        : typed.get(field.external());
                if (value == null && field.defaultText() != null) {
                    value = engine.parseValue(field.platformType(), field.defaultText(), field.platform());
                }
                if (value == null) {
                    if (field.required() && !update) {
                        return Inbound.failed(FlowReason.REQUIRED_MISSING);
                    }
                    continue;
                }
                if (field.in() == null) {
                    value = convertIn(field, value);
                    if (value == null) {
                        if (field.conversion() == CompiledMapping.Conversion.VALUE_MAP) {
                            return Inbound.failed(FlowReason.VALUE_NOT_MAPPED);
                        }
                        continue;
                    }
                }
                String text = engine.formatValue(value);
                if (text != null) {
                    assignments.add(new Assignment(field.platform(), text));
                }
            } catch (ExpressionException failed) {
                return Inbound.failed(FlowReason.TRANSFORM_FAILED);
            }
        }
        return new Inbound(assignments, skipped, null);
    }

    private static boolean wins(Conflict conflict, boolean externalNewer) {
        return switch (conflict) {
            case EXTERNAL_WINS -> true;
            case PLATFORM_WINS -> false;
            case PLATFORM_UNLESS_NEWER -> externalNewer;
        };
    }

    private static Instant externalChangedAt(CompiledMapping mapping, CanonicalEntity entity) {
        if (mapping.changedAtField() == null) {
            return null;
        }
        Object raw = entity.fields().get(mapping.changedAtField());
        if (!(raw instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /** A value the platform holds, in the form the external side carries; null when the value map has no partner. */
    private Object convertOut(CompiledMapping.Field field, Object value) {
        return switch (field.conversion()) {
            case NONE -> value;
            case TRIM -> trimmed(value);
            case VALUE_MAP -> field.toExternal().get(value.toString());
            case NUMBER_SCALE -> ((BigDecimal) value).multiply(field.factor());
        };
    }

    /** A value the external side carries, in the form the platform holds; null when the value map has no partner. */
    private Object convertIn(CompiledMapping.Field field, Object value) {
        return switch (field.conversion()) {
            case NONE -> value;
            case TRIM -> trimmed(value);
            case VALUE_MAP -> field.toPlatform().get(value.toString());
            case NUMBER_SCALE -> ((BigDecimal) value).divide(field.factor(), new MathContext(DIVISION_DIGITS))
                    .stripTrailingZeros();
        };
    }

    private static Object trimmed(Object value) {
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    /** A value read from an external payload, given the type the mapping says it has; null when it does not fit. */
    private Object typed(ExpressionType type, Object raw, String field) {
        try {
            return switch (type) {
                case TEXT -> raw instanceof BigDecimal number ? number.toPlainString() : raw.toString();
                case NUMBER -> raw instanceof BigDecimal number ? number
                        : raw instanceof String text ? new BigDecimal(text.strip()) : null;
                case BOOLEAN -> raw instanceof Boolean flag ? flag
                        : raw instanceof String text && ("true".equals(text) || "false".equals(text))
                                ? Boolean.valueOf(text) : null;
                case DATE, DATETIME, TIME -> raw instanceof String text ? engine.parseValue(type, text, field) : null;
                case NONE -> null;
            };
        } catch (NumberFormatException | ExpressionException unreadable) {
            return null;
        }
    }

    /**
     * A fingerprint of what is sent or stored: the same fields and values give the same text, whatever the order. It
     * decides only whether anything changed since the last time; it is never shown and never leaves the database.
     */
    static String hash(Map<String, ?> values) {
        StringBuilder text = new StringBuilder();
        new TreeMap<String, Object>(values).forEach((name, value) -> text.append(name).append('=')
                .append(value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : value)
                .append('\n'));
        return sha256(text.toString());
    }

    /** The hash of the values of an entity. */
    static String hash(CanonicalEntity entity) {
        return hash(entity.fields());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }
}
