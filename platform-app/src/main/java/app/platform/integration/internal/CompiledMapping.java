package app.platform.integration.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A mapping made ready to run (Sprint 28, ADR-0218): checked against the fields the object has now, its expressions
 * compiled by the expression engine, its conversions turned into lookups. Immutable and safe to share between threads.
 * It holds text of the organization, so it prints nothing of it.
 *
 * @param entity the kind of entity on the external side
 * @param conflict who wins for a field that goes both ways when both sides changed it
 * @param changedAtField the external field that says when the entity last changed, or null
 * @param inboundCreate whether a change for an unknown entity may make a record
 * @param inboundUpdate whether a change for a known entity may change its record
 * @param fields the field maps in the order the definition lists them
 */
record CompiledMapping(String entity, Conflict conflict, String changedAtField, boolean inboundCreate,
        boolean inboundUpdate, List<Field> fields) {

    CompiledMapping {
        fields = List.copyOf(fields);
    }

    /** Who wins for a field that goes both ways. */
    enum Conflict {
        /** The platform, unless the outside change is newer than the record. */
        PLATFORM_UNLESS_NEWER,
        /** The outside change, always. */
        EXTERNAL_WINS,
        /** The platform, always: only fields that come in alone are written from outside. */
        PLATFORM_WINS
    }

    /** Which way a field goes. */
    enum Direction {
        OUTBOUND,
        INBOUND,
        BOTH;

        boolean out() {
            return this != INBOUND;
        }

        boolean in() {
            return this != OUTBOUND;
        }
    }

    /** The closed list of conversions. */
    enum Conversion {
        NONE,
        VALUE_MAP,
        NUMBER_SCALE,
        TRIM
    }

    /**
     * One field map, ready.
     *
     * @param external the external field name
     * @param platform the platform field API name
     * @param direction which way it goes
     * @param externalType what the external side carries
     * @param platformType what the platform field holds, as the expression engine sees it
     * @param required whether a value must exist
     * @param defaultText the default as written, or null
     * @param conversion the conversion for a direction that has no expression
     * @param toExternal the value map from the platform's value to the external one
     * @param toPlatform the same map the other way round
     * @param factor the factor of a number scale
     * @param out the compiled expression that gives the value to send, or null
     * @param in the compiled expression that gives the value to store, or null
     */
    record Field(String external, String platform, Direction direction, ExpressionType externalType,
            ExpressionType platformType, boolean required, String defaultText, Conversion conversion,
            Map<String, String> toExternal, Map<String, String> toPlatform, BigDecimal factor, Compiled out,
            Compiled in) {

        Field {
            toExternal = Map.copyOf(toExternal);
            toPlatform = Map.copyOf(toPlatform);
        }

        @Override
        public String toString() {
            return "Field[redacted]";
        }
    }

    @Override
    public String toString() {
        return "CompiledMapping[" + fields.size() + " fields]";
    }
}
