package app.platform.data.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.security.MemberAccess;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Record types on records (ADR-0093). A record type is a variant of an object: it offers some of the fields and allows
 * some of the values of a picklist ({@code ADR-0064}); this class enforces both when a record is written.
 *
 * <ul>
 * <li>An object with no record types has records with none, and a write that names one is refused.</li>
 * <li>A type that is chosen must exist, be active and be one the member's profile may use (ADR-0098: a type with no
 * profile assigned is open to every profile, otherwise only those named; a type the profile may not use answers as a
 * type that does not exist). A record that already has a type keeps it even after the type is switched off or the
 * profile loses it (existing records keep reading and saving), but another record cannot be given it.</li>
 * <li>A create that names no type gets the default type of the member's profile, then the default type of the object if
 * the profile may use it, otherwise none.</li>
 * <li>A write may name only the fields the type offers, and a picklist value only from the subset the type allows
 * (a value the record holds already is accepted: nothing is rewritten when a type changes).</li>
 * </ul>
 */
@Component
class RecordTypeCheck {

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Za-z0-9_]{0,55}__c");
    private static final String PLACE = "recordType";

    private final Metadata metadata;
    private final MemberAccess members;

    RecordTypeCheck(Metadata metadata, MemberAccess members) {
        this.metadata = metadata;
        this.members = members;
    }

    /**
     * Decides the record type a write leaves the record with.
     *
     * @param membershipId the member who writes; their profile decides which types they may use
     * @param requested the type named in the request, or null for "no change" (an update) or "the default" (a create)
     * @param held the type the record has now, or null (always null for a create)
     * @return the type, or empty for a record without one
     */
    Optional<RecordType> resolve(ObjectDefinition object, UUID membershipId, String requested, String held,
            Findings findings) {
        List<RecordType> types = metadata.recordTypes(object.apiName());
        if (types.isEmpty() && requested == null) {
            return Optional.empty();
        }
        UUID profile = types.isEmpty() ? null : members.profileOf(membershipId).orElse(null);
        if (requested == null) {
            if (held != null) {
                return types.stream().filter(type -> type.apiName().equals(held)).findFirst();
            }
            return metadata.defaultRecordTypeFor(object.apiName(), profile);
        }
        if (!NAME.matcher(requested).matches()) {
            findings.add(PLACE, "Give the API name of a record type of the object.");
            return Optional.empty();
        }
        Optional<RecordType> found = metadata.recordTypesFor(object.apiName(), profile).stream()
                .filter(type -> type.apiName().equals(requested)).findFirst();
        if (found.isEmpty() && requested.equals(held)) {
            // The record has it already: naming it again changes nothing, even if the profile has lost it since.
            found = types.stream().filter(type -> type.apiName().equals(requested)).findFirst();
        }
        if (found.isEmpty()) {
            findings.add(PLACE, "This object has no such record type.");
        } else if (!found.get().active() && !requested.equals(held)) {
            findings.add(PLACE, "This record type is not in use.");
        }
        return found;
    }

    /** The fields of the object the type offers; a record without a type is offered every field. */
    static boolean offers(RecordType type, FieldDefinition field) {
        return type == null || type.allFields() || field.kind() == DefinitionKind.SYSTEM
                || type.availableFields().contains(field.apiName());
    }

    /** A write may name only the fields the type offers (clearing a field the type does not offer is allowed). */
    void checkOffered(RecordType type, ObjectDefinition object, Collection<String> valued, Findings findings) {
        if (type == null || type.allFields()) {
            return;
        }
        for (String key : valued) {
            object.field(key).filter(field -> !offers(type, field))
                    .ifPresent(field -> findings.add(key, "This field is not offered by the record type."));
        }
    }

    /**
     * A picklist value must be one the type allows. Run after the values have the right type.
     *
     * @param written the values being written, in their stored shape
     * @param held the values the record holds now
     */
    static void checkPicklists(RecordType type, Map<String, Object> written, Map<String, Object> held,
            Findings findings) {
        if (type == null) {
            return;
        }
        type.picklistValues().forEach((field, allowed) -> {
            Object value = written.get(field);
            if (value == null) {
                return;
            }
            Object before = held.get(field);
            List<?> chosen = value instanceof List<?> list ? list : List.of(value);
            List<?> kept = before instanceof List<?> list ? list : before == null ? List.of() : List.of(before);
            for (Object one : chosen) {
                if (!allowed.contains(String.valueOf(one)) && !kept.contains(one)) {
                    findings.add(field, "This value is not allowed for the record type.");
                    return;
                }
            }
        });
    }
}
