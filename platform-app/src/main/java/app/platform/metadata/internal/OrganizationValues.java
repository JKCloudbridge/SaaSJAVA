package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.security.Decisions;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platformapi.AddFieldValueRequest;
import app.platformapi.AddedFieldValueView;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.FieldSettings;
import app.platformapi.UpdateFieldRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The organization's own list of values for a picklist the platform defines (FA2A, ADR-0182). The platform owns the
 * field (its name, its type, that it is dependent and on which field); the organization owns the list of values and the
 * pairs. Two doors lead here, with different authority and the same rules:
 *
 * <ul>
 * <li>{@link #update}: the administrator's field update, which on such a field may change the values and the pairs and
 * nothing else. It is an ordinary change of the lifecycle (change sets, releases, rollback), so it carries the version
 * read and is judged by the dependency graph like any other.</li>
 * <li>{@link #add}: a member who holds the ability to add values and may edit the field adds one value, with the pairs
 * it goes with, from the form. It only adds, takes effect at once and is audited by counts.</li>
 * </ul>
 *
 * <p>A value is never removed or renamed, only switched off (the rule every picklist has). A list equal to the
 * platform's starting list is stored as "use the platform's list", so a rollback goes back to the file. Runs inside the
 * transaction {@link MetadataAdministration} opened. Nothing typed is written to a log or an audit record.
 */
@Service
class OrganizationValues {

    private final MetadataCatalogue catalogue;
    private final OrganizationValuesStore store;
    private final MetadataVersions versions;
    private final ConfigCodec codec;
    private final MetadataAudit audit;
    private final Decisions decisions;

    OrganizationValues(MetadataCatalogue catalogue, OrganizationValuesStore store, MetadataVersions versions,
            ConfigCodec codec, MetadataAudit audit, Decisions decisions) {
        this.catalogue = catalogue;
        this.store = store;
        this.versions = versions;
        this.codec = codec;
        this.audit = audit;
        this.decisions = decisions;
    }

    // ---- the administrator's change ----

    /**
     * Applies a field update to a platform picklist the organization owns the list of.
     *
     * @param context the rules' view of the world around the field
     * @throws ProtectedDefinition when the request changes anything but the values and the pairs
     * @throws ApiException {@code VALIDATION_ERROR} for a list the rules refuse, {@code CONCURRENT_MODIFICATION} when
     *         the list was changed since it was read
     */
    void update(Caller caller, FieldDefinition field, UpdateFieldRequest request, FieldRules.Context context) {
        if (!platformPartsKept(field, request)) {
            throw new ProtectedDefinition(caller.userId(), "field.update", field.permissionKey());
        }
        FieldRules.Checked checked = FieldRules.checkChange(field.type(), field.configuration(), field.required(),
                field.unique(), field.defaultValue(), request.settings(), context);
        PicklistConfiguration wanted = (PicklistConfiguration) checked.configuration();
        refuseBadCodes(field, wanted);
        if (request.version() != field.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (wanted.equals(field.configuration())) {
            return;
        }
        FieldDefinition platform = catalogue.platformDefinition(field.objectApiName(), field.apiName())
                .orElseThrow(() -> ApiException.notFound("This field does not exist."));
        boolean reset = wanted.equals(platform.configuration());
        versions.wrote();
        OrganizationValuesStore.Row row = store.lock(field.objectApiName(), field.apiName()).orElse(null);
        String stored = reset ? OrganizationValuesStore.PLATFORM_LIST : codec.write(wanted);
        boolean written;
        if (row == null) {
            written = request.version() == 0 && store.insert(field.objectApiName(), field.apiName(), stored,
                    caller.userId());
        } else {
            written = row.version() + 1 == request.version()
                    && store.update(row.id(), row.version(), stored, caller.userId());
        }
        if (!written) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.fieldValuesChanged(caller.userId(), field.objectApiName(), field.apiName(), wanted.values().size(),
                wanted.dependency() == null ? 0 : wanted.dependency().pairs(), reset);
    }

    /** Whether the request leaves everything the platform decides as the platform has it. */
    private static boolean platformPartsKept(FieldDefinition field, UpdateFieldRequest request) {
        return field.label().equals(request.label() == null ? "" : request.label().strip())
                && field.description().equals(request.description() == null ? "" : request.description().strip())
                && field.required() == Boolean.TRUE.equals(request.required())
                && field.unique() == (Boolean.TRUE.equals(request.unique())
                        || Boolean.TRUE.equals(request.externalId()))
                && field.indexed() == Boolean.TRUE.equals(request.indexed())
                && field.externalId() == Boolean.TRUE.equals(request.externalId())
                && field.searchable() == Boolean.TRUE.equals(request.searchable())
                && Objects.equals(field.defaultValue(), blankToNull(request.defaultValue()));
    }

    // ---- a member adds a value ----

    /**
     * Adds a value to the list of a picklist and the pairs it goes with.
     *
     * @throws ApiException {@code NOT_FOUND} for a field that is not such a picklist or one the member cannot read,
     * {@code FORBIDDEN} when they may not edit it, {@code VALIDATION_ERROR} for a value or pair the rules refuse
     */
    AddedFieldValueView add(Caller caller, String objectApiName, String fieldApiName, AddFieldValueRequest request,
            FieldRules.Context context) {
        ObjectDefinition object = catalogue.object(objectApiName).orElseThrow(OrganizationValues::missing);
        FieldDefinition field = object.field(fieldApiName).orElseThrow(OrganizationValues::missing);
        if (!field.organizationValues() || field.retired() || field.type() != FieldType.PICKLIST) {
            throw missing();
        }
        ObjectAccess access = decisions.accessTo(caller.membershipId(), objectApiName);
        if (!access.readableFields().contains(fieldApiName)) {
            throw missing();
        }
        if (!access.editableFields().contains(fieldApiName)
                || !(access.can(ObjectAction.CREATE) || access.can(ObjectAction.UPDATE))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String value = normalised(field, request.value());
        String label = request.label() == null || request.label().isBlank() ? value : request.label().strip();
        List<String> controlling = request.controllingValues() == null ? List.of()
                : request.controllingValues().stream().map(String::strip).distinct().toList();

        // What is there now decides whether anything is to be done; a repeat changes nothing and writes nothing.
        PicklistConfiguration now = (PicklistConfiguration) field.configuration();
        Change plan = plan(object, field, now, value, label, controlling);
        if (plan.next().equals(now)) {
            return view(plan, false);
        }
        // Writing: read the database itself from here on, queue behind another addition to the same list, and decide
        // again on what is there once it is our turn.
        versions.wrote();
        store.insert(objectApiName, fieldApiName, OrganizationValuesStore.PLATFORM_LIST, caller.userId());
        OrganizationValuesStore.Row row = store.lock(objectApiName, fieldApiName)
                .orElseThrow(() -> new ApiException(ErrorCode.CONCURRENT_MODIFICATION));
        ObjectDefinition fresh = catalogue.object(objectApiName).orElseThrow(OrganizationValues::missing);
        FieldDefinition freshField = fresh.field(fieldApiName).orElseThrow(OrganizationValues::missing);
        PicklistConfiguration current = (PicklistConfiguration) freshField.configuration();
        Change change = plan(fresh, freshField, current, value, label, controlling);
        if (change.next().equals(current)) {
            return view(change, false);
        }
        FieldSettings settings = FieldRules.settingsOf(change.next());
        FieldRules.Checked checked = FieldRules.checkChange(freshField.type(), current, freshField.required(),
                freshField.unique(), freshField.defaultValue(), settings, context);
        PicklistConfiguration next = (PicklistConfiguration) checked.configuration();
        refuseBadCodes(freshField, next);
        if (!store.update(row.id(), row.version(), codec.write(next), caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.fieldValueAdded(caller.userId(), objectApiName, fieldApiName, change.newValue(), change.pairsAdded());
        return view(change, true);
    }

    /** What adding the value to the list as it is would make. */
    private static Change plan(ObjectDefinition object, FieldDefinition field, PicklistConfiguration now, String value,
            String label, List<String> controlling) {
        Dependency dependency = now.dependency();
        if (dependency == null && !controlling.isEmpty()) {
            throw ApiException.validation("controllingValues", "This picklist does not depend on another field.");
        }
        if (dependency != null) {
            if (controlling.isEmpty()) {
                throw ApiException.validation("controllingValues", "Choose the value of the other field this one "
                        + "goes with.");
            }
            FieldDefinition other = object.field(dependency.controllingField())
                    .filter(candidate -> candidate.configuration() instanceof PicklistConfiguration)
                    .orElseThrow(() -> ApiException.validation("controllingValues", "The other field is not "
                            + "available."));
            Set<String> chosen = new HashSet<>();
            ((PicklistConfiguration) other.configuration()).values().stream().filter(PicklistValue::active)
                    .forEach(known -> chosen.add(known.value()));
            if (!chosen.containsAll(controlling)) {
                throw ApiException.validation("controllingValues", "Choose a value of the other field that exists "
                        + "and is in use.");
            }
        }
        boolean isNew = now.values().stream().noneMatch(known -> known.value().equals(value));
        if (!isNew && now.values().stream().anyMatch(known -> known.value().equals(value) && !known.active())) {
            throw ApiException.validation("value", "This value exists but is switched off. Ask an administrator to "
                    + "switch it on.");
        }
        List<PicklistValue> values = new ArrayList<>(now.values());
        if (isNew) {
            values.add(new PicklistValue(value, label, true));
        }
        Dependency next = dependency;
        int pairsAdded = 0;
        if (dependency != null) {
            Map<String, List<String>> allowed = new LinkedHashMap<>();
            dependency.allowed().forEach((key, list) -> allowed.put(key, new ArrayList<>(list)));
            for (String key : controlling) {
                List<String> list = allowed.computeIfAbsent(key, ignored -> new ArrayList<>());
                if (!list.contains(value)) {
                    list.add(value);
                    pairsAdded++;
                }
            }
            next = new Dependency(dependency.controllingField(), allowed);
        }
        String shownLabel = isNew ? label : now.values().stream().filter(known -> known.value().equals(value))
                .findFirst().map(PicklistValue::label).orElse(label);
        List<String> goesWith = next == null ? List.of() : next.allowed().entrySet().stream()
                .filter(entry -> entry.getValue().contains(value)).map(Map.Entry::getKey).sorted().toList();
        return new Change(new PicklistConfiguration(values, next), value, shownLabel, goesWith, isNew, pairsAdded);
    }

    /** What an addition would leave, and what to tell the caller. */
    private record Change(PicklistConfiguration next, String value, String label, List<String> goesWith,
            boolean newValue, int pairsAdded) {
    }

    private static AddedFieldValueView view(Change change, boolean added) {
        return new AddedFieldValueView(change.value(), change.label(), change.goesWith(), added);
    }

    // ---- shared ----

    /** A short code is kept in capitals, so that two spellings of one code cannot both exist. */
    private static String normalised(FieldDefinition field, String typed) {
        String value = typed == null ? "" : typed.strip();
        return field.codeValues() ? value.toUpperCase(Locale.ROOT) : value;
    }

    private static void refuseBadCodes(FieldDefinition field, FieldConfiguration configuration) {
        if (field.codeValues() && configuration instanceof PicklistConfiguration picklist) {
            List<String> problems = FieldRules.codeProblems(picklist.values());
            if (!problems.isEmpty()) {
                throw ApiException.validation("settings.values", problems.get(0));
            }
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static ApiException missing() {
        return ApiException.notFound("This field does not exist.");
    }
}
