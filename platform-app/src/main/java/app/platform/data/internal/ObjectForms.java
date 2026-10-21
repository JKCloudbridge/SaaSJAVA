package app.platform.data.internal;

import app.platform.data.Records;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PageLayout;
import app.platform.metadata.RecordType;
import app.platform.security.Ability;
import app.platform.security.MemberAccess;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.Permissions;
import app.platformapi.ApiException;
import app.platformapi.FormActionsView;
import app.platformapi.FormFieldView;
import app.platformapi.FormLayoutView;
import app.platformapi.FormRecordTypeView;
import app.platformapi.FormSectionView;
import app.platformapi.ObjectFormView;
import app.platformapi.PicklistDependency;
import app.platformapi.PicklistOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a screen needs to draw the list and the forms of one object for the signed-in member (Sprint 19, ADR-0129).
 * Everything the browser would otherwise have to decide is decided here, from the same sources the write pipeline and
 * the query engine use, so a screen and the API cannot drift apart:
 *
 * <ul>
 * <li><strong>Which fields exist for this member.</strong> Only a field the member may read is described; the others
 * are absent, as on every read (ADR-0092). A record type offers only some fields ({@link RecordTypeCheck#offers}), and
 * its picklist choices are the subset it allows.</li>
 * <li><strong>What they may type.</strong> {@code editable} is the field permission, the object permission, the rule
 * that a person writes no system field and no calculated one, and the page layout's behaviour for the field, together.
 * The pipeline judges the write again; this only keeps the screen from offering what will be refused.</li>
 * <li><strong>Which layout.</strong> The one a record page names (Sprint 20: {@code layoutName}, when it still exists),
 * else the one the object uses for the member's profile and the record type ({@link Metadata#layoutFor}, ADR-0071),
 * reduced to the fields in front of this member. A layout always shows the
 * required fields of its object (the metadata refuses one that does not, ADR-0069), so a form made from it can be
 * saved and nothing is added to it here; when the object has no layout the platform lays the fields out itself.</li>
 * <li><strong>Which record types.</strong> The ones the member's profile may use (ADR-0098); the type a record already
 * holds is described even when the profile has lost it ("a record keeps the type it has"). A type that is asked for by
 * name and that the profile may not use answers like a type that does not exist.</li>
 * </ul>
 *
 * <p>Nothing here reads a record's values, so nothing typed by a person is logged or audited; the labels are text an
 * administrator typed and the answer never prints them.
 */
@Service
@Transactional(readOnly = true)
class ObjectForms {

    /**
     * The system fields a screen can show: the others (the identifier, who created, who last changed) are not part of
     * what a record's answer carries, and a field that cannot be shown must not be offered as a column.
     */
    private static final Set<String> SHOWN_SYSTEM = Set.of("sequence", "ownerId", "createdAt", "updatedAt");

    private static final String EDITABLE = "EDITABLE";
    private static final String READ_ONLY = "READ_ONLY";
    private static final String REQUIRED = "REQUIRED";
    private static final String HIDDEN = "HIDDEN";
    private static final String SYSTEM = "SYSTEM";

    /** One field of a page layout as an administrator placed it. */
    private record Placed(String field, String behaviour) {
    }

    /** One block of a page layout as an administrator wrote it. */
    private record Block(String label, int columns, List<Placed> fields) {
    }

    private final RecordGate gate;
    private final FieldSecurity security;
    private final Metadata metadata;
    private final MemberAccess members;
    private final Records records;
    private final Permissions permissions;

    ObjectForms(RecordGate gate, FieldSecurity security, Metadata metadata, MemberAccess members, Records records,
            Permissions permissions) {
        this.permissions = permissions;
        this.gate = gate;
        this.security = security;
        this.metadata = metadata;
        this.members = members;
        this.records = records;
    }

    /**
     * Describes the object for the member.
     *
     * @param objectApiName the object
     * @param requestedType the record type to describe the object for, or null; one the member's profile may use
     * @param recordId a record whose own record type decides when none is requested (the screen that edits it), or null
     * @throws ApiException {@code NOT_FOUND} for an object the member may not read or that does not exist, or a record
     *         they cannot reach; {@code VALIDATION_ERROR} at {@code recordType} for a type they may not use
     */
    ObjectFormView describe(String objectApiName, String requestedType, UUID recordId) {
        return describe(objectApiName, requestedType, recordId, null);
    }

    /**
     * Describes the object for the member with the page layout a record page names.
     *
     * @param layoutName the API name of the layout the details show, or null for the member's own resolution; a name
     *        that is not a layout of the object is ignored, so a page whose layout was removed still opens
     */
    ObjectFormView describe(String objectApiName, String requestedType, UUID recordId, String layoutName) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        ObjectDefinition object = passed.object();
        ObjectAccess access = security.accessOf(passed.membershipId(), object.apiName());
        UUID profile = members.profileOf(passed.membershipId()).orElse(null);

        List<RecordType> all = metadata.recordTypes(object.apiName());
        List<RecordType> usable = all.isEmpty() ? List.of() : metadata.recordTypesFor(object.apiName(), profile);
        String held = recordId == null ? null : records.get(object.apiName(), recordId).recordType();
        RecordType selected = select(object, all, usable, profile, requestedType, held);
        String selectedName = selected == null ? null : selected.apiName();

        PageLayout page = layoutNamed(object.apiName(), layoutName)
                .or(() -> layoutNamed(object.apiName(),
                        metadata.layoutFor(object.apiName(), profile, selectedName).layout().orElse(null)))
                .orElse(null);
        List<Block> blocks = page == null ? List.of() : blocksOf(page);
        Map<String, String> behaviours = new HashMap<>();
        blocks.forEach(block -> block.fields().forEach(placed -> behaviours.putIfAbsent(placed.field(),
                placed.behaviour())));

        boolean mayAddValues = permissions.has(passed.membershipId(), Ability.FIELD_VALUES_ADD);
        List<FormFieldView> fields = new ArrayList<>();
        for (FieldDefinition field : object.fields()) {
            if (access.readableFields().contains(field.apiName()) && !field.retired() && shown(field)
                    && RecordTypeCheck.offers(selected, field)) {
                fields.add(describe(object, field, access, behaviours.get(field.apiName()), selected,
                        mayAddValues));
            }
        }

        boolean searchEnabled = object.fields().stream().anyMatch(field -> field.searchable() && !field.retired()
                && access.readableFields().contains(field.apiName()));
        String title = TitleFields.of(object, access.readableFields()).map(FieldDefinition::apiName).orElse(null);
        FormActionsView actions = new FormActionsView(access.can(ObjectAction.CREATE), access.can(ObjectAction.UPDATE),
                access.can(ObjectAction.DELETE));
        return new ObjectFormView(object.apiName(), object.label(), object.pluralLabel(), title, actions,
                typeViews(all, usable, selected, profile, object.apiName()), selectedName, searchEnabled, fields,
                layout(page, blocks, fields));
    }

    private Optional<PageLayout> layoutNamed(String objectApiName, String name) {
        if (name == null) {
            return Optional.empty();
        }
        return metadata.layouts(objectApiName).stream().filter(layout -> layout.apiName().equals(name)).findFirst();
    }

    // ---- record types ----

    private RecordType select(ObjectDefinition object, List<RecordType> all, List<RecordType> usable, UUID profile,
            String requested, String held) {
        if (requested != null) {
            // The same words as the write pipeline: a type the profile may not use is a type that does not exist.
            RecordType found = usable.stream().filter(type -> type.apiName().equals(requested)).findFirst()
                    .orElseThrow(() -> ApiException.validation("recordType", "This object has no such record type."));
            if (!found.active() && !requested.equals(held)) {
                throw ApiException.validation("recordType", "This record type is not in use.");
            }
            return found;
        }
        if (held != null) {
            return all.stream().filter(type -> type.apiName().equals(held)).findFirst().orElse(null);
        }
        return metadata.defaultRecordTypeFor(object.apiName(), profile).orElse(null);
    }

    private List<FormRecordTypeView> typeViews(List<RecordType> all, List<RecordType> usable, RecordType selected,
            UUID profile, String objectApiName) {
        if (all.isEmpty()) {
            return List.of();
        }
        Set<String> usableNames = new HashSet<>();
        usable.forEach(type -> usableNames.add(type.apiName()));
        Optional<String> defaultName = metadata.defaultRecordTypeFor(objectApiName, profile).map(RecordType::apiName);
        Map<String, RecordType> shown = new LinkedHashMap<>();
        usable.stream().filter(RecordType::active).forEach(type -> shown.put(type.apiName(), type));
        if (selected != null) {
            shown.putIfAbsent(selected.apiName(), selected);
        }
        return shown.values().stream().map(type -> new FormRecordTypeView(type.apiName(), type.label(),
                type.description(), defaultName.filter(type.apiName()::equals).isPresent(),
                type.active() && usableNames.contains(type.apiName()))).toList();
    }

    // ---- fields ----

    private static boolean shown(FieldDefinition field) {
        return field.kind() != DefinitionKind.SYSTEM || SHOWN_SYSTEM.contains(field.apiName());
    }

    private FormFieldView describe(ObjectDefinition object, FieldDefinition field, ObjectAccess access,
            String behaviour, RecordType selected, boolean mayAddValues) {
        boolean calculated = field.type().calculated();
        boolean editable = access.editableFields().contains(field.apiName()) && field.kind() != DefinitionKind.SYSTEM
                && !calculated && !field.platformWritten() && !READ_ONLY.equals(behaviour)
                && !HIDDEN.equals(behaviour);
        boolean required = (field.required() || REQUIRED.equals(behaviour)) && !calculated;

        Integer maxLength = null;
        Integer digits = null;
        Integer precision = null;
        Integer scale = null;
        Integer width = null;
        List<PicklistOption> values = null;
        String target = null;
        String targetLabel = null;
        boolean targetOpenable = false;
        String resultType = null;
        String prefix = null;
        switch (field.configuration()) {
            case TextConfiguration text -> maxLength = text.maxLength();
            case NumberConfiguration number -> digits = number.digits();
            case DecimalConfiguration decimal -> {
                precision = decimal.precision();
                scale = decimal.scale();
            }
            case PicklistConfiguration picklist -> values = choices(field, picklist, selected);
            case ReferenceConfiguration reference -> {
                target = reference.targetObject();
                Optional<ObjectDefinition> pointedAt = metadata.object(target);
                targetLabel = pointedAt.map(ObjectDefinition::label).orElse(target);
                // An object a platform module owns (a user) has no screen of its own among the records.
                targetOpenable = pointedAt.map(found -> found.managedBy() == null).orElse(false);
            }
            case FormulaConfiguration formula -> resultType = formula.resultType().name();
            case AutoNumberConfiguration number -> {
                prefix = number.prefix();
                width = number.width();
            }
            case NoConfiguration none -> {
                // A type with no setting has nothing to add.
            }
        }
        QueryAnalyzer.Querying querying = QueryAnalyzer.queryingOf(field);
        boolean canAdd = mayAddValues && field.organizationValues() && editable
                && (access.can(ObjectAction.CREATE) || access.can(ObjectAction.UPDATE));
        return new FormFieldView(field.apiName(), field.label(), field.description(), field.kind().name(),
                field.type().name(), required, field.unique(), editable, calculated, field.defaultValue(), maxLength,
                digits, precision, scale, values, target, targetLabel, targetOpenable, resultType, prefix, width,
                field.searchable(), field.externalId(), querying.sortable(), querying.operators(),
                dependencyOf(object, field, access, selected), canAdd, field.codeValues() ? Boolean.TRUE : null);
    }

    /**
     * The rule of a dependent picklist for the screen, or null: left out when the field depends on nothing and when the
     * member cannot see the controlling field (it is absent for them, or the record type does not offer it), so the
     * answer says nothing about a field they cannot read. The server enforces the rule on every write whatever this
     * says.
     */
    private static PicklistDependency dependencyOf(ObjectDefinition object, FieldDefinition field,
            ObjectAccess access, RecordType selected) {
        if (!(field.configuration() instanceof PicklistConfiguration picklist) || picklist.dependency() == null) {
            return null;
        }
        String controlling = picklist.dependency().controllingField();
        boolean visible = access.readableFields().contains(controlling) && object.field(controlling)
                .filter(other -> !other.retired() && RecordTypeCheck.offers(selected, other)).isPresent();
        return visible ? new PicklistDependency(controlling, picklist.dependency().allowed()) : null;
    }

    /** The choices of a picklist: the ones the record type allows (all of them when it does not restrict). */
    private static List<PicklistOption> choices(FieldDefinition field, PicklistConfiguration picklist,
            RecordType selected) {
        List<String> allowed = selected == null ? null : selected.picklistValues().get(field.apiName());
        return picklist.values().stream().filter(value -> allowed == null || allowed.contains(value.value()))
                .map(value -> new PicklistOption(value.value(), value.label(), value.active())).toList();
    }

    // ---- layout ----

    private static List<Block> blocksOf(PageLayout page) {
        List<Block> blocks = new ArrayList<>();
        if (!(page.definition().get("sections") instanceof List<?> sections)) {
            return blocks;
        }
        for (Object section : sections) {
            if (!(section instanceof Map<?, ?> map)) {
                continue;
            }
            String label = map.get("label") instanceof String text ? text : "";
            int columns = map.get("columns") instanceof Number number && number.intValue() == 2 ? 2 : 1;
            List<Placed> placed = new ArrayList<>();
            if (map.get("fields") instanceof List<?> fields) {
                for (Object item : fields) {
                    if (item instanceof Map<?, ?> entry && entry.get("field") instanceof String name) {
                        placed.add(new Placed(name, entry.get("behaviour") instanceof String behaviour ? behaviour
                                : EDITABLE));
                    }
                }
            }
            blocks.add(new Block(label, columns, placed));
        }
        return blocks;
    }

    private static FormLayoutView layout(PageLayout page, List<Block> blocks, List<FormFieldView> fields) {
        Set<String> known = new HashSet<>();
        fields.forEach(field -> known.add(field.apiName()));
        if (page != null) {
            Set<String> placed = new HashSet<>();
            List<FormSectionView> sections = new ArrayList<>();
            for (Block block : blocks) {
                List<String> names = block.fields().stream()
                        .filter(entry -> known.contains(entry.field()) && !HIDDEN.equals(entry.behaviour()))
                        .map(Placed::field).filter(placed::add).toList();
                if (!names.isEmpty()) {
                    sections.add(new FormSectionView(block.label(), block.columns(), names));
                }
            }
            if (!sections.isEmpty()) {
                return new FormLayoutView("LAYOUT", page.apiName(), sections);
            }
            // A layout in which the member sees no field at all would give an empty screen: fall back to the
            // platform's own.
        }
        List<FormSectionView> sections = new ArrayList<>();
        List<String> details = fields.stream().filter(field -> !SYSTEM.equals(field.kind()))
                .map(FormFieldView::apiName).toList();
        List<String> system = fields.stream().filter(field -> SYSTEM.equals(field.kind()))
                .map(FormFieldView::apiName).toList();
        if (!details.isEmpty()) {
            sections.add(new FormSectionView("Details", 2, details));
        }
        if (!system.isEmpty()) {
            sections.add(new FormSectionView("System information", 2, system));
        }
        return new FormLayoutView("GENERATED", null, sections);
    }
}
