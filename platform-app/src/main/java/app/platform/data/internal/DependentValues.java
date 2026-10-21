package app.platform.data.internal;

import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The stage of the write pipeline that judges a dependent picklist (FA2A, ADR-0177): a picklist whose values depend on
 * the value of another picklist of the same object. The rule lives in the field's configuration; this class only
 * applies it to a write.
 *
 * <ul>
 * <li><b>Only a write that touches the pair is judged.</b> The pair is the dependent field and its controlling field;
 * touching one means the write sets or clears it to something other than the record holds. A record that holds a pair
 * that is no longer allowed (the rule changed after the record was saved) is therefore never blocked by an edit of
 * something else, and a write that sends the same pair back changes nothing.</li>
 * <li><b>A value needs a controlling value that allows it.</b> A non-empty dependent value is allowed when the record
 * has a controlling value and the rule lists the dependent value for it. No controlling value allows nothing.</li>
 * <li><b>A record type narrows, never widens.</b> What the record type allows is judged by the stage before this one
 * ({@link RecordTypeCheck}); both must hold, which is the intersection of the record type's values and the row of the
 * rule.</li>
 * </ul>
 *
 * <p>The refusal says which field it is about and names no value: the values are text of the organization. A member who
 * may not read the controlling field is not told its name.
 */
@Component
class DependentValues {

    /** The refusal; the text after it is the label of the controlling field, or "another field". */
    static final String MISMATCH = "This value does not go with the chosen value of ";
    private static final String UNREADABLE = "another field";

    /**
     * Judges every dependent picklist of the object the write touches.
     *
     * @param merged the values the record will have after the write
     * @param typed the values written (in their stored shape)
     * @param cleared the fields the write empties
     * @param held the values the record has now (empty for a create)
     */
    void check(ObjectDefinition object, ObjectAccess access, Map<String, Object> merged, Map<String, Object> typed,
            List<String> cleared, Map<String, Object> held, Findings findings) {
        for (FieldDefinition field : object.fields()) {
            if (field.retired() || !(field.configuration() instanceof PicklistConfiguration picklist)
                    || picklist.dependency() == null) {
                continue;
            }
            Dependency dependency = picklist.dependency();
            String controlling = dependency.controllingField();
            if (!touched(field.apiName(), typed, cleared, held) && !touched(controlling, typed, cleared, held)) {
                continue;
            }
            Object value = merged.get(field.apiName());
            if (value == null) {
                continue;
            }
            Object chosen = merged.get(controlling);
            List<String> allowed = chosen instanceof String key ? dependency.allowed().get(key) : null;
            if (allowed == null || !allowed.contains(String.valueOf(value))) {
                findings.add(FieldSecurity.place(access, field.apiName()),
                        MISMATCH + nameOf(object, access, controlling) + ".");
            }
        }
    }

    /** Whether the write sets or empties the field to something other than the record holds. */
    private static boolean touched(String field, Map<String, Object> typed, List<String> cleared,
            Map<String, Object> held) {
        if (typed.containsKey(field)) {
            return !Objects.equals(typed.get(field), held.get(field));
        }
        return cleared.contains(field) && held.containsKey(field);
    }

    private static String nameOf(ObjectDefinition object, ObjectAccess access, String controlling) {
        if (!access.readableFields().contains(controlling)) {
            return UNREADABLE;
        }
        return object.field(controlling).map(FieldDefinition::label).orElse(UNREADABLE);
    }
}
