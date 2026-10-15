package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueryPlan;
import app.platform.data.SlotCopy;
import app.platform.data.StorageStrategy;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.RecordAction;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The write pipeline (ADR-0091): everything a create or an update goes through between the gate ({@link RecordGate}:
 * who, which organization, which object, may they use it) and the storage, in the order of the delivery plan:
 *
 * <ol>
 * <li><b>shape</b> of the keys and values ({@link RecordValues}, Sprint 14);</li>
 * <li><b>record type</b>: it exists, is in use, and offers the fields written ({@link RecordTypeCheck});</li>
 * <li><b>field permission</b>: every field named is one the member may edit ({@link FieldSecurity}); the record
 * permission of Sprint 17 goes between the gate and this step;</li>
 * <li><b>type</b>: each value belongs in its field, and a picklist value is one the record type allows
 * ({@link FieldValue});</li>
 * <li><b>required</b>: a required field and a master-detail have a value after the write;</li>
 * <li><b>relationship</b>: a linked record exists, is live and is of the target object, in this organization;</li>
 * <li><b>unique</b>: no other live record holds the value of a unique field, or links the same two parents of a
 * junction (ADR-0111; the database refuses what slips past, with the same answer);</li>
 * <li><b>rules</b>: every validation rule is judged ({@link Calculations});</li>
 * <li><b>formulas</b>: calculated in order, each result before the next, and stored with the record.</li>
 * </ol>
 *
 * <p>A stage reports <em>every</em> problem it finds, in one answer, and the pipeline stops after the first stage that
 * found any (a later stage would judge values an earlier one has called wrong). Nothing is changed by a refused write:
 * the auto-number is taken after the last check that can refuse, in the same transaction, so a refusal gives back
 * the number with everything else. The seam of the typed copies of indexed fields ({@link RecordSlots}) is the last
 * step before the storage.
 */
@Component
class WritePipeline {

    /** The stages in the order they run and answer. Tests hold the pipeline to this list. */
    static final List<String> STAGES = List.of("shape", "record type", "field permission", "type", "required",
            "relationship", "unique", "rule", "formula");

    private static final String MISSING_LINK = "The linked record does not exist.";
    // The words say that the value is in use, never whose record holds it: it may be one the member cannot see, and the
    // check runs across the whole organization whatever anyone can see (ADR-0125).
    static final String VALUE_TAKEN = "This value is already in use. This field must be unique.";
    static final String PAIR_TAKEN = "This combination is already in use.";

    /**
     * What a write that passed will store.
     *
     * @param recordType the record type the record has afterwards, or null
     * @param set the values to store, formula results and auto-numbers included
     * @param clear the fields that lose their value
     * @param slots the typed copies of the indexed fields the record has afterwards (ADR-0102)
     */
    record Plan(String recordType, Map<String, Object> set, List<String> clear, SlotCopy slots) {

        Plan {
            set = Map.copyOf(set);
            clear = List.copyOf(clear);
        }

        /** The names of every field the write stores or clears, in a stable order. */
        List<String> names() {
            Set<String> all = new LinkedHashSet<>(set.keySet().stream().sorted().toList());
            all.addAll(clear.stream().sorted().toList());
            return List.copyOf(all);
        }
    }

    private final RecordValues shape;
    private final RecordTypeCheck recordTypes;
    private final FieldSecurity security;
    private final Calculations calculations;
    private final AutoNumbers autoNumbers;
    private final StorageStrategy storage;
    private final Metadata metadata;
    private final RecordSlots slots;
    private final RecordLinks recordLinks;
    private final Map<String, ReferenceDirectory> directories;
    private final RecordVisibility visibility;

    WritePipeline(RecordValues shape, RecordTypeCheck recordTypes, FieldSecurity security,
            Calculations calculations, AutoNumbers autoNumbers, StorageStrategy storage, Metadata metadata,
            RecordSlots slots, RecordLinks recordLinks, List<ReferenceDirectory> directories,
            RecordVisibility visibility) {
        this.visibility = visibility;
        this.shape = shape;
        this.recordTypes = recordTypes;
        this.security = security;
        this.calculations = calculations;
        this.autoNumbers = autoNumbers;
        this.storage = storage;
        this.metadata = metadata;
        this.slots = slots;
        this.recordLinks = recordLinks;
        Map<String, ReferenceDirectory> byObject = new HashMap<>();
        directories.forEach(directory -> byObject.put(directory.object(), directory));
        this.directories = Map.copyOf(byObject);
    }

    /**
     * Runs a write through the stages.
     *
     * @param passed what the gate let through
     * @param access what the member may do with the object and its fields (once per request)
     * @param current the record as it is now, or null for a create
     * @param requestedType the record type named in the request, or null
     * @param set the values given
     * @param clear the fields to empty
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} with every problem of the first failing stage
     */
    Plan run(RecordGate.Passed passed, ObjectAccess access, DataRecord current, String requestedType,
            Map<String, Object> set, List<String> clear) {
        ObjectDefinition object = passed.object();
        boolean creating = current == null;
        Map<String, Object> held = current == null ? Map.of() : current.fields();
        String heldType = current == null ? null : current.recordType();

        // 1. shape
        RecordValues.Checked checked = shape.check(set, clear, !creating && requestedType == null);

        // 2. record type
        Findings typeFindings = new Findings();
        Optional<RecordType> resolved = recordTypes.resolve(object, passed.membershipId(), requestedType, heldType,
                typeFindings);
        typeFindings.throwIfAny();
        RecordType type = resolved.orElse(null);
        String typeName = resolved.map(RecordType::apiName).orElse(heldType);

        // 3. field permission (and the fields the record type offers)
        Findings permission = new Findings();
        List<String> named = new ArrayList<>(checked.set().keySet());
        named.addAll(checked.clear());
        List<String> allowed = security.checkWrite(object, access, named, permission);
        recordTypes.checkOffered(type, object, allowed.stream().filter(checked.set()::containsKey).toList(),
                permission);
        permission.throwIfAny();

        Map<String, Object> values = new LinkedHashMap<>(checked.set());
        List<String> cleared = new ArrayList<>(checked.clear());
        if (creating) {
            applyDefaults(object, type, values);
        }
        emptyTextIsNoValue(values, cleared);

        // 4. type
        Findings typing = new Findings();
        Map<String, Object> typed = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            FieldValue.Outcome outcome = FieldValue.check(object.field(key).orElseThrow(), value, held.get(key));
            if (outcome.valid()) {
                typed.put(key, outcome.value());
            } else {
                typing.add(key, outcome.problem());
            }
        });
        if (typing.isEmpty()) {
            RecordTypeCheck.checkPicklists(type, typed, held, typing);
        }
        typing.throwIfAny();

        Map<String, Object> merged = new HashMap<>(held);
        cleared.forEach(merged::remove);
        merged.putAll(typed);

        // 5. required
        Findings required = new Findings();
        checkRequired(object, type, access, merged, Set.copyOf(typed.keySet()), Set.copyOf(cleared), creating,
                required);
        required.throwIfAny();

        // The shared lock of the object and the constraints that are enforced, read together (ADR-0101, ADR-0111).
        RecordSlots.Active active = slots.begin(object);

        // 6. relationship
        Findings links = new Findings();
        checkLinks(passed.membershipId(), object, typed, held, links);
        // Until the database enforces the pair of parents of a junction, a lock in the pipeline keeps it unique.
        boolean pairEnforced = active.constraints().stream().anyMatch(row -> row.kind() == UniqueStore.Kind.PAIR);
        if (links.isEmpty() && !pairEnforced && RecordLinks.isJunction(object) && masterChanged(object, typed, held)
                && recordLinks.pairTaken(object, merged, current == null ? null : current.id())) {
            links.add(RecordLinks.masterDetails(object).get(1).apiName(), PAIR_TAKEN);
        }
        links.throwIfAny();

        // 7. unique (the database refuses a duplicate that slips past this, and the answer is the same)
        Findings unique = new Findings();
        UUID self = current == null ? null : current.id();
        for (RecordSlots.Probe probe : slots.probes(active, object, merged, held)) {
            if (storage.valueTaken(object.apiName(), probe.bit(), probe.values(), self)) {
                unique.add(FieldSecurity.place(access, probe.place()), probe.pair() ? PAIR_TAKEN : VALUE_TAKEN);
            }
        }
        unique.throwIfAny();

        // 7. rules (a rule that reads a formula field judges the value this save gives it)
        Findings rules = new Findings();
        Map<String, Object> ruleValues = Calculations.engineValues(object, merged);
        if (calculations.rulesReadFormulas(object.apiName())) {
            calculations.formulas(object, ruleValues, access, new Findings());
        }
        calculations.rules(object, ruleValues, access, rules);
        rules.throwIfAny();

        // 8. formulas, after the auto-numbers (the last thing that can lock a row)
        Map<String, Object> generated = creating ? autoNumbers(passed, object, type) : Map.of();
        merged.putAll(generated);
        Findings formulas = new Findings();
        Map<String, Object> results = calculations.formulas(object, Calculations.engineValues(object, merged), access,
                formulas);
        formulas.throwIfAny();

        Map<String, Object> toStore = new LinkedHashMap<>(typed);
        toStore.putAll(generated);
        List<String> toClear = new ArrayList<>(cleared);
        results.forEach((field, result) -> {
            if (result == null) {
                if (held.containsKey(field)) {
                    toClear.add(field);
                }
            } else if (!sameValue(held.get(field), result)) {
                toStore.put(field, result);
            }
        });

        // persist: the seam for the typed copies of indexed fields, then the storage (the caller)
        Map<String, Object> after = new HashMap<>(merged);
        results.forEach((field, result) -> {
            if (result == null) {
                after.remove(field);
            } else {
                after.put(field, result);
            }
        });
        SlotCopy copies = slots.copyIndexed(active, object, after);
        return new Plan(typeName, toStore, toClear, copies);
    }

    // ---- defaults ----

    /** A field default applies on create when the key is absent and the field is one a record of the type offers. */
    private static void applyDefaults(ObjectDefinition object, RecordType type, Map<String, Object> values) {
        for (FieldDefinition field : object.fields()) {
            if (field.defaultValue() != null && !values.containsKey(field.apiName()) && writable(field)
                    && RecordTypeCheck.offers(type, field)) {
                Object value = FieldValue.defaultOf(field);
                if (value != null) {
                    values.put(field.apiName(), value);
                }
            }
        }
    }

    private static boolean writable(FieldDefinition field) {
        return field.kind() != DefinitionKind.SYSTEM && !field.type().calculated() && !field.retired();
    }

    /** An empty text is no value: it clears the field and never satisfies a required field. */
    private static void emptyTextIsNoValue(Map<String, Object> values, List<String> cleared) {
        values.entrySet().removeIf(entry -> {
            boolean empty = entry.getValue() instanceof String text && text.isEmpty();
            if (empty && !cleared.contains(entry.getKey())) {
                cleared.add(entry.getKey());
            }
            return empty;
        });
    }

    // ---- required ----

    private void checkRequired(ObjectDefinition object, RecordType type, ObjectAccess access,
            Map<String, Object> merged, Set<String> written, Set<String> cleared, boolean creating,
            Findings findings) {
        for (FieldDefinition field : object.fields()) {
            boolean mustHave = field.required() || field.type() == FieldType.MASTER_DETAIL;
            if (!mustHave || !writable(field) || !RecordTypeCheck.offers(type, field)
                    || merged.containsKey(field.apiName())) {
                continue;
            }
            // A save is not blocked by a required field the member may not edit and did not touch: they could never
            // fix it. A create is: the record would be saved without the value.
            boolean touched = written.contains(field.apiName()) || cleared.contains(field.apiName());
            if (creating || touched || access.editableFields().contains(field.apiName())) {
                boolean readable = access.readableFields().contains(field.apiName());
                findings.add(FieldSecurity.place(access, field.apiName()),
                        readable ? "A value is required." : "A required value is missing.");
            }
        }
    }

    // ---- relationships ----

    /** Whether the write sets or moves either master of a junction (only then can the pair be a new one). */
    private static boolean masterChanged(ObjectDefinition object, Map<String, Object> typed, Map<String, Object> held) {
        return RecordLinks.masterDetails(object).stream().anyMatch(
                field -> typed.containsKey(field.apiName())
                        && !Objects.equals(typed.get(field.apiName()), held.get(field.apiName())));
    }

    private void checkLinks(UUID membershipId, ObjectDefinition object, Map<String, Object> typed,
            Map<String, Object> held, Findings findings) {
        Map<String, Map<String, UUID>> wanted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : typed.entrySet()) {
            FieldDefinition field = object.field(entry.getKey()).orElseThrow();
            if (!field.type().isReference() || Objects.equals(entry.getValue(), held.get(entry.getKey()))) {
                continue;
            }
            ReferenceConfiguration reference = (ReferenceConfiguration) field.configuration();
            if (field.type() == FieldType.MASTER_DETAIL && held.containsKey(field.apiName())
                    && !reference.reparentable()) {
                findings.add(field.apiName(), "The master of this record cannot be changed.");
                continue;
            }
            Optional<ObjectDefinition> target = metadata.object(reference.targetObject());
            if (target.isEmpty()) {
                findings.add(field.apiName(), MISSING_LINK);
            } else if (target.get().managedBy() != null) {
                ReferenceDirectory directory = directories.get(target.get().apiName());
                if (directory == null) {
                    findings.add(field.apiName(), "Links to this kind of object are not available yet.");
                } else if (!directory.exists(UUID.fromString((String) entry.getValue()))) {
                    findings.add(field.apiName(), MISSING_LINK);
                }
            } else {
                wanted.computeIfAbsent(target.get().apiName(), name -> new LinkedHashMap<>())
                        .put(field.apiName(), UUID.fromString((String) entry.getValue()));
            }
        }
        // One question per target object, however many fields point at it.
        wanted.forEach((targetObject, byField) -> {
            Set<UUID> reachable = reachable(membershipId, targetObject, byField.values());
            byField.forEach((field, id) -> {
                if (!reachable.contains(id)) {
                    findings.add(field, MISSING_LINK);
                }
            });
        });
    }

    /**
     * The linked records the member may link to: live records of the target object that they can read (ADR-0125). A
     * record that does not exist, one in the recycle bin, one of another object, one the member cannot read and every
     * record of an object they may not use all give the same answer, so a write is no way to find out what exists.
     */
    private Set<UUID> reachable(UUID membershipId, String targetObject, Collection<UUID> ids) {
        ObjectAccess access = security.accessOf(membershipId, targetObject);
        if (!access.can(ObjectAction.READ)) {
            return Set.of();
        }
        ObjectDefinition target = metadata.object(targetObject).orElseThrow();
        Optional<QueryPlan.Node> readable = visibility.constraintFor(target, membershipId, access, RecordAction.READ);
        return readable.isPresent() ? storage.liveIds(targetObject, ids, readable.get())
                : storage.liveIds(targetObject, ids);
    }

    // ---- auto-numbers ----

    /** The next number of every auto-number field the record type offers, in field order (so locks are taken alike). */
    private Map<String, Object> autoNumbers(RecordGate.Passed passed, ObjectDefinition object, RecordType type) {
        Map<String, Object> numbers = new TreeMap<>();
        for (FieldDefinition field : object.fields()) {
            if (field.type() == FieldType.AUTO_NUMBER && !field.retired() && RecordTypeCheck.offers(type, field)) {
                numbers.put(field.apiName(), autoNumbers.next(object.apiName(), field.apiName(),
                        (AutoNumberConfiguration) field.configuration(), passed.userId()));
            }
        }
        return numbers;
    }

    /** Whether a stored value and a calculated one are the same value (numbers by value, not by spelling). */
    static boolean sameValue(Object before, Object after) {
        if (before instanceof Number left && after instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return Objects.equals(before, after);
    }
}
