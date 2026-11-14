package app.platform.data.internal;

import app.platform.data.NumberSeriesException;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.AdvanceNumberingCounterRequest;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.NumberingCountersView;
import app.platformapi.NumberingFieldView;
import app.platformapi.NumberingPreviewView;
import app.platformapi.NumberingSeriesView;
import app.platformapi.NumberingSettingKind;
import app.platformapi.NumberingSettingView;
import app.platformapi.NumberingSettingsView;
import app.platformapi.PicklistOption;
import app.platformapi.PreviewNumberingRequest;
import app.platformapi.ResetNumberingFormatRequest;
import app.platformapi.SaveNumberingFormatRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administration of closed formats. One ability, one settings lock and no cache (FA6B, ADR-0197). */
@Service
@Transactional
class NumberingManagement {

    private static final long MAX_COUNT = 999999999999L;
    private static final int COUNTER_PAGE = 50;
    private final NumberSeriesCatalogue catalogue;
    private final NumberingFormats formats;
    private final NumberingStore store;
    private final Metadata metadata;
    private final Permissions permissions;
    private final TenantContexts contexts;
    private final AuditRecorder audit;
    private final MeterRegistry meters;

    NumberingManagement(NumberSeriesCatalogue catalogue, NumberingFormats formats, NumberingStore store,
            Metadata metadata, Permissions permissions, TenantContexts contexts, AuditRecorder audit,
            MeterRegistry meters) {
        this.catalogue = catalogue;
        this.formats = formats;
        this.store = store;
        this.metadata = metadata;
        this.permissions = permissions;
        this.contexts = contexts;
        this.audit = audit;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    NumberingSettingsView list() {
        require();
        List<NumberingSeriesView> result = new ArrayList<>();
        for (NumberSeriesCatalogue.Series series : catalogue.all().values().stream()
                .sorted(java.util.Comparator.comparing(NumberSeriesCatalogue.Series::key)).toList()) {
            metadata.object(series.object()).ifPresent(object -> result.add(describe(series, object)));
        }
        return new NumberingSettingsView(result);
    }

    /**
     * The combinations that have a counter, a page at a time (FA11B, ADR-0206). The scope is shown as it is stored,
     * with the marker of the newer scope form taken off and the field names left out, so a person reads
     * {@code WI/PRD}. Needs the same ability as the rest of the screen; reads counters only.
     */
    @Transactional(readOnly = true)
    NumberingCountersView counters(String key, String cursor) {
        require();
        NumberSeriesCatalogue.Series series = available(key);
        store.lock(key, false);
        int offset = offset(cursor);
        List<NumberingStore.Counter> found = store.counters(series.key(), offset, COUNTER_PAGE + 1);
        boolean more = found.size() > COUNTER_PAGE;
        List<NumberingStore.Counter> page = more ? found.subList(0, COUNTER_PAGE) : found;
        List<NumberingCountersView.Counter> rows = page.stream().map(counter -> new NumberingCountersView.Counter(
                readable(counter.scope()), counter.nextValue(), Math.max(0, counter.nextValue() - 1))).toList();
        return new NumberingCountersView(rows, more ? Integer.toString(offset + COUNTER_PAGE) : null);
    }

    /** The cursor is only the number of counters already seen: nothing typed is ever put in an address. */
    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor);
            if (offset < 0) {
                throw new NumberFormatException();
            }
            return offset;
        } catch (NumberFormatException notANumber) {
            throw ApiException.validation("cursor", "The page you asked for does not exist.");
        }
    }

    /** The older scope form is the values joined; the newer one starts with a marker and names each field. */
    private static String readable(String scope) {
        String[] parts = scope.split("/");
        if (parts.length > 0 && parts[0].equals("numbering-scope-v2")) {
            List<String> shown = new ArrayList<>();
            if (parts.length > 1) {
                shown.add(parts[1]);
            }
            for (int at = 3; at < parts.length; at += 2) {
                shown.add(parts[at]);
            }
            return String.join("/", shown);
        }
        return scope;
    }

    NumberingSettingView save(String key, SaveNumberingFormatRequest request) {
        TenantContext caller = require();
        NumberSeriesCatalogue.Series series = available(key);
        store.lock(key, true);
        ObjectDefinition object = object(series);
        String selector = selector(series, object, request.kind(), request.selectorValue());
        NumberFormat format = checked(series, object, request.kind(), request.format());
        if (!store.save(key, request.kind().name(), selector, format.text(), request.version(), caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        changed("format.saved", caller, series, request.kind().name(), format.parts().size());
        NumberingStore.Setting saved = store.setting(key, request.kind().name(), selector).orElseThrow();
        return new NumberingSettingView(request.kind(), selector, "", true, format.text(), "ORGANIZATION",
                saved.version() + 1, format.width());
    }

    NumberingSettingsView reset(String key, ResetNumberingFormatRequest request) {
        TenantContext caller = require();
        NumberSeriesCatalogue.Series series = available(key);
        store.lock(key, true);
        String selector = selector(series, object(series), request.kind(), request.selectorValue());
        if (!store.reset(key, request.kind().name(), selector, request.version(), caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        changed("format.reset", caller, series, request.kind().name(), 0);
        return list();
    }

    @Transactional(readOnly = true)
    NumberingPreviewView preview(String key, PreviewNumberingRequest request) {
        require();
        NumberSeriesCatalogue.Series series = available(key);
        store.lock(key, false);
        ObjectDefinition object = object(series);
        String choice = selector(series, object, request.kind(), request.selectorValue());
        Map<String, Object> values = values(object, request.values());
        if (request.kind() == NumberingSettingKind.CATEGORY && !series.selector().isEmpty()) {
            values.put(series.selector(), choice);
        }
        validPairs(object, values);
        NumberFormat format;
        if (request.format() == null) {
            format = effective(series, values);
        } else {
            format = checked(series, object, request.kind(), request.format());
        }
        return preview(series, format, values);
    }

    NumberingPreviewView advance(String key, AdvanceNumberingCounterRequest request) {
        TenantContext caller = require();
        NumberSeriesCatalogue.Series series = available(key);
        store.lock(key, true);
        Map<String, Object> values = values(object(series), request.values());
        validPairs(object(series), values);
        NumberFormat format = effective(series, values);
        if (!format.hasCounter()) {
            throw ApiException.validation("nextValue", "This format has no running count.");
        }
        NumberingPreviewView before = preview(series, format, values);
        if (!before.missingFields().isEmpty()) {
            throw ApiException.validation("values", "Fill in the parts needed for this number first.");
        }
        long wanted = request.nextValue();
        if (wanted < before.nextValue()) {
            throw ApiException.validation("nextValue", "The next count can only go up, never down.");
        }
        if (wanted > before.nextValue()) {
            store.advance(series.key(), NumberingFormats.scope(series, format, values), wanted, caller.userId());
            changed("counter.raised", caller, series, "COUNTER", format.parts().size());
        }
        return preview(series, format, values);
    }

    private NumberingSeriesView describe(NumberSeriesCatalogue.Series series, ObjectDefinition object) {
        store.lock(series.key(), false);
        List<NumberingStore.Setting> overrides = store.settings(series.key());
        List<NumberingSettingView> settings = new ArrayList<>();
        for (Map.Entry<String, NumberFormat> entry : series.defaults().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            String label = entry.getKey().equals("DEFAULT") ? "Default with optional part"
                    : "Default without optional part";
            settings.add(setting(overrides, entry.getKey(), "", label, true, entry.getValue(), "SHIPPED"));
        }
        if (series.selector().isEmpty()) {
            settings.add(setting(overrides, "CATEGORY", "", object.label(), true,
                    series.formats().get(""), "SHIPPED"));
        } else {
            PicklistConfiguration picklist = (PicklistConfiguration) object.field(series.selector())
                    .orElseThrow().configuration();
            for (PicklistValue value : picklist.values()) {
                NumberFormat shipped = series.formats().get(value.value());
                String fallbackKind = fallbackKind(series, object, value.value());
                NumberFormat fallback = overrides.stream().filter(row -> row.kind().equals(fallbackKind))
                        .findFirst().map(row -> NumberFormat.parse(row.text()))
                        .orElse(series.defaults().get(fallbackKind));
                NumberFormat base = shipped == null ? fallback : shipped;
                if (base != null) {
                    settings.add(setting(overrides, "CATEGORY", value.value(), value.label(), value.active(),
                            base, shipped == null ? "DEFAULT" : "SHIPPED"));
                }
            }
        }
        List<NumberingFieldView> fields = object.fields().stream().filter(field -> !field.retired()
                        && !field.apiName().equals(series.field())
                        && (field.type() == FieldType.TEXT || field.type() == FieldType.PICKLIST && field.codeValues()))
                .map(NumberingManagement::field).toList();
        return new NumberingSeriesView(series.key(), series.object(), object.label(), series.selector(),
                series.optionalField(), fields, settings);
    }

    private static NumberingFieldView field(FieldDefinition field) {
        List<PicklistOption> values = field.configuration() instanceof PicklistConfiguration picklist
                ? picklist.values().stream().map(value -> new PicklistOption(value.value(), value.label(),
                        value.active())).toList() : List.of();
        return new NumberingFieldView(field.apiName(), field.label(), values);
    }

    private static String fallbackKind(NumberSeriesCatalogue.Series series, ObjectDefinition object, String choice) {
        return object.field(series.optionalField())
                .filter(field -> field.configuration() instanceof PicklistConfiguration)
                .map(field -> ((PicklistConfiguration) field.configuration()).dependency())
                .filter(dependency -> dependency.controllingField().equals(series.selector()))
                .filter(dependency -> !dependency.allowed().getOrDefault(choice, List.of()).isEmpty())
                .map(dependency -> "DEFAULT").orElse("WITHOUT_OPTIONAL");
    }

    private static NumberingSettingView setting(List<NumberingStore.Setting> overrides, String kind, String choice,
            String label, boolean active,
            NumberFormat base, String source) {
        NumberingStore.Setting override = overrides.stream()
                .filter(row -> row.kind().equals(kind) && row.selector().equals(choice)).findFirst().orElse(null);
        NumberFormat format = override == null ? base : NumberFormat.parse(override.text());
        return new NumberingSettingView(NumberingSettingKind.valueOf(kind), choice, label, active, format.text(),
                override == null ? source : "ORGANIZATION", override == null ? 0L : override.version() + 1,
                format.width());
    }

    private NumberingPreviewView preview(NumberSeriesCatalogue.Series series, NumberFormat format,
            Map<String, Object> values) {
        List<String> missing = new ArrayList<>();
        if (!series.selector().isEmpty() && !code(values.get(series.selector()))) {
            missing.add(series.selector());
        }
        format.fields().stream().filter(name -> !code(values.get(name))).filter(name -> !missing.contains(name))
                .forEach(missing::add);
        if (!missing.isEmpty()) {
            return new NumberingPreviewView(null, null, format.width(),
                    format.fields().stream().distinct().toList(), missing,
                    "Choose the parts of the number to see an example.");
        }
        long next = format.hasCounter()
                ? store.nextValue(series.key(), NumberingFormats.scope(series, format, values)) : 0;
        if (next > MAX_COUNT) {
            throw ApiException.validation("nextValue", "This count has reached its limit. Ask your administrator.");
        }
        return new NumberingPreviewView(format.render(values, next), format.hasCounter() ? next : null, format.width(),
                format.fields().stream().distinct().toList(), List.of(),
                "An example, not a reservation. Another approval may take this number first.");
    }

    private NumberFormat checked(NumberSeriesCatalogue.Series series, ObjectDefinition object,
            NumberingSettingKind kind, String text) {
        try {
            NumberFormat format = NumberFormat.parse(text);
            NumberSeriesCatalogue.validateFormat(object, series.field(), format);
            if (kind != NumberingSettingKind.CATEGORY) {
                if (series.defaults().isEmpty()) {
                    throw new IllegalArgumentException("This series has no default formats.");
                }
                NumberSeriesCatalogue.checkDefault(format, series.selector(), series.optionalField(),
                        kind == NumberingSettingKind.DEFAULT);
            }
            return format;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            // Parser and field-validator words are fixed and never repeat the submitted text.
            throw ApiException.validation("format", refused.getMessage());
        }
    }

    private static String selector(NumberSeriesCatalogue.Series series, ObjectDefinition object,
            NumberingSettingKind kind, String choice) {
        if (kind != NumberingSettingKind.CATEGORY || series.selector().isEmpty()) {
            if (!choice.isEmpty()) {
                throw ApiException.validation("selectorValue", "This setting does not take a category.");
            }
            return "";
        }
        PicklistConfiguration picklist = (PicklistConfiguration) object.field(series.selector())
                .orElseThrow().configuration();
        if (picklist.values().stream().noneMatch(value -> value.value().equals(choice))) {
            throw ApiException.validation("selectorValue", "Choose a category that exists in the organization's list.");
        }
        return choice;
    }

    private static Map<String, Object> values(ObjectDefinition object, Map<String, String> given) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : given.entrySet()) {
            FieldDefinition field = object.field(entry.getKey()).orElseThrow(() -> ApiException.validation("values",
                    "A supplied part is not a field of this object."));
            String value = entry.getValue();
            if (value == null || !value.isEmpty() && !NumberFormat.CODE.matcher(value).matches()) {
                throw ApiException.validation("values", "Each part must be a short code of at most 10 characters.");
            }
            if (field.configuration() instanceof PicklistConfiguration picklist && !value.isEmpty()
                    && picklist.values().stream().noneMatch(option -> option.value().equals(value))) {
                throw ApiException.validation("values", "Choose a part that exists in the organization's list.");
            }
            values.put(entry.getKey(), value);
        }
        return values;
    }

    private NumberFormat effective(NumberSeriesCatalogue.Series series, Map<String, Object> values) {
        try {
            return formats.resolve(series, values);
        } catch (NumberSeriesException refused) {
            throw ApiException.validation("values", "Choose the category and the parts needed for this number.");
        }
    }

    private static void validPairs(ObjectDefinition object, Map<String, Object> values) {
        for (FieldDefinition field : object.fields()) {
            if (!(field.configuration() instanceof PicklistConfiguration picklist) || picklist.dependency() == null
                    || !(values.get(field.apiName()) instanceof String value) || value.isEmpty()) {
                continue;
            }
            Object controlling = values.get(picklist.dependency().controllingField());
            if (!(controlling instanceof String code)
                    || !picklist.dependency().allowed().getOrDefault(code, List.of()).contains(value)) {
                throw ApiException.validation("values", "Choose parts that go together in the organization's lists.");
            }
        }
    }

    private static boolean code(Object value) {
        return value instanceof String text && NumberFormat.CODE.matcher(text).matches();
    }

    private NumberSeriesCatalogue.Series available(String key) {
        NumberSeriesCatalogue.Series series = catalogue.series(key)
                .orElseThrow(() -> ApiException.notFound("This number series does not exist."));
        object(series);
        return series;
    }

    private ObjectDefinition object(NumberSeriesCatalogue.Series series) {
        return metadata.object(series.object()).orElseThrow(() -> ApiException.notFound("This number series "
                + "does not exist."));
    }

    private TenantContext require() {
        TenantContext caller = contexts.current().filter(context -> context.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound("This page belongs to an organization."));
        if (!permissions.has(caller.membershipId(), Ability.NUMBERING_MANAGE)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return caller;
    }

    private void changed(String action, TenantContext caller, NumberSeriesCatalogue.Series series, String kind,
            int parts) {
        audit.record(AuditRecord.of("numbering." + action, AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(caller.tenantId()).onObject(series.object(), null).with("series", series.key())
                .with("setting", kind).with("parts", Integer.toString(parts)));
        meters.counter("platform.numbering.change", "action", action).increment();
    }
}
