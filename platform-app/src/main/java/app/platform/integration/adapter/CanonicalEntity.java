package app.platform.integration.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The neutral shape between a platform record and an external system's entity (Sprint 28, ADR-0216): a kind, the
 * identifier the entity has on the other side, and typed field values by external field name. It is what a mapping
 * produces from a record and what an adapter turns into a request, and the other way round for a change that comes in.
 *
 * <p>A value is one of {@code String}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate}, {@code Instant} or
 * {@code LocalTime}; a field with no value is absent or null. The model holds neither a platform field's permission nor
 * a record's security: those were decided before it was made. It is data of the organization and of the external
 * system, so it never prints its values.
 *
 * @param kind the kind of entity on the external side, as the mapping names it
 * @param externalId the identifier on the external side, or null when it has none yet (a create)
 * @param fields the values by external field name
 */
public record CanonicalEntity(String kind, String externalId, Map<String, Object> fields) {

    public CanonicalEntity {
        Objects.requireNonNull(kind, "kind");
        Map<String, Object> copy = new LinkedHashMap<>();
        fields.forEach((name, value) -> {
            if (value != null) {
                copy.put(name, value);
            }
        });
        fields = Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() {
        return "CanonicalEntity[" + kind + ", " + fields.size() + " fields]";
    }
}
