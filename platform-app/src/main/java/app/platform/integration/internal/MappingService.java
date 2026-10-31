package app.platform.integration.internal;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionType;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.Mapper.Assignment;
import app.platform.integration.internal.Mapper.Inbound;
import app.platform.integration.internal.Mapper.Outbound;
import app.platform.integration.internal.MappingStore.Mapping;
import app.platform.integration.internal.MappingStore.Version;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Ability;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.IntegrationView;
import app.platformapi.MappingCatalogueView;
import app.platformapi.MappingChoice;
import app.platformapi.MappingDryRunRequest;
import app.platformapi.MappingDryRunView;
import app.platformapi.MappingSkippedField;
import app.platformapi.MappingSpec;
import app.platformapi.MappingVersionView;
import app.platformapi.MappingView;
import app.platformapi.SaveMappingRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The life of a mapping (Sprint 28, ADR-0217): draft, check against the object, try on a sample, publish, stop. The
 * published version is what sends and receives; a draft does nothing until it is published, and what was published
 * never changes, so an event in flight is mapped by the version it started under. A mapping is not metadata (no change
 * sets, no releases): it belongs to a connection, and a connection to an environment.
 *
 * <p>Every call decides who may ask first (membership, ability, plan feature), then what the state allows. The
 * definition is text of the organization: it is checked and stored, never logged, audited or put in a metric; the audit
 * records and the metrics carry identifiers and counts only. Nothing here calls an external system.
 */
@Service
@Transactional
class MappingService {

    private static final int MAX_SAMPLE = 1000;
    private static final int HISTORY = 20;

    private final ConnectionStore connections;
    private final MappingStore store;
    private final MappingCompiler compiler;
    private final Mapper mapper;
    private final Metadata metadata;
    private final IntegrationAccess access;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    MappingService(ConnectionStore connections, MappingStore store, MappingCompiler compiler, Mapper mapper,
            Metadata metadata, IntegrationAccess access, AuditRecorder audit, MeterRegistry meters, Clock clock) {
        this.connections = connections;
        this.store = store;
        this.compiler = compiler;
        this.mapper = mapper;
        this.metadata = metadata;
        this.access = access;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    /** The choices a definition can make, for the screens; the same for everyone who may see mappings. */
    @Transactional(readOnly = true)
    MappingCatalogueView catalogue() {
        access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW);
        return new MappingCatalogueView(
                List.of(new MappingChoice("PLATFORM_UNLESS_NEWER", "The platform, unless the change is newer",
                                "A change from outside wins only when its changed-at time is later than the record's "
                                        + "last change."),
                        new MappingChoice("EXTERNAL_WINS", "The external system", "A change from outside always wins."),
                        new MappingChoice("PLATFORM_WINS", "The platform", "Fields that go both ways are never "
                                + "overwritten from outside; fields that only come in still are.")),
                List.of(new MappingChoice("OUTBOUND", "Out", "Sent to the external system."),
                        new MappingChoice("INBOUND", "In", "Written from changes the external system sends."),
                        new MappingChoice("BOTH", "Both ways", "Sent, and written from changes that come in.")),
                List.of(new MappingChoice("TEXT", "Text", "Words and letters."),
                        new MappingChoice("NUMBER", "Number", "A number."),
                        new MappingChoice("BOOLEAN", "Yes or no", "True or false."),
                        new MappingChoice("DATE", "Date", "A day, written 2030-01-31."),
                        new MappingChoice("DATETIME", "Date and time", "Written 2030-01-31T09:30:00Z."),
                        new MappingChoice("TIME", "Time", "Written 09:30.")),
                List.of(new MappingChoice("NONE", "None", "The value goes as it is; the types must match."),
                        new MappingChoice("TRIM", "Trim spaces", "Text, without spaces at either end."),
                        new MappingChoice("VALUE_MAP", "Value map", "Each value is exchanged for its partner, both "
                                + "ways.", "pairs"),
                        new MappingChoice("NUMBER_SCALE", "Number scale", "Multiplied on the way out and divided on "
                                + "the way in, for example 100 for cents.", "factor")));
    }

    @Transactional(readOnly = true)
    List<MappingView> list(String connectionName) {
        access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW);
        Connection connection = connection(connectionName);
        return store.mappingsOf(connection.id()).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    MappingView get(String connectionName, String object) {
        access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW);
        Connection connection = connection(connectionName);
        return view(store.mappingFor(connection.id(), object)
                .orElseThrow(() -> ApiException.notFound("This object has no mapping on this connection.")));
    }

    MappingView saveDraft(String connectionName, String object, SaveMappingRequest request) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW,
                Ability.MAPPINGS_MANAGE);
        Connection connection = connection(connectionName);
        ObjectDefinition definition = mappable(object);
        MappingCompiler.Result checked = compiler.compile(request.entity(), request.definition(), definition);
        if (!checked.ok()) {
            throw ApiException.validation(prefixed(checked.problems()));
        }
        Mapping mapping = store.mappingFor(connection.id(), object).orElse(null);
        if (mapping == null) {
            if (store.mappingByEntity(connection.id(), request.entity()).isPresent()) {
                throw ApiException.validation("entity", "Another object of this connection already uses this entity.");
            }
            mapping = store.insertMapping(connection.id(), object, request.entity(), caller.userId());
        } else if (!mapping.entity().equals(request.entity())) {
            throw ApiException.validation("entity", "The entity of a mapping cannot be changed once it is made.");
        }
        String hash = hash(request.definition());
        Optional<Version> draft = store.draftOf(mapping.id());
        if (draft.isPresent()) {
            if (draft.get().version() != request.version()
                    || !store.updateDraft(draft.get().id(), request.version(), request.definition(), hash,
                            caller.userId())) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
        } else {
            if (request.version() != 0) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            store.insertDraft(mapping.id(), store.nextNumber(mapping.id()), request.definition(), hash,
                    caller.userId());
        }
        record("drafted", caller, connection, mapping, request.definition().fields().size());
        return view(mapping);
    }

    void discardDraft(String connectionName, String object, long version) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW,
                Ability.MAPPINGS_MANAGE);
        Connection connection = connection(connectionName);
        Mapping mapping = store.mappingFor(connection.id(), object)
                .orElseThrow(() -> ApiException.notFound("This object has no mapping on this connection."));
        Version draft = store.draftOf(mapping.id())
                .orElseThrow(() -> ApiException.notFound("There is no draft to discard."));
        if (!store.deleteDraft(draft.id(), version)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        record("discarded", caller, connection, mapping, 0);
    }

    MappingView publish(String connectionName, String object, long version) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW,
                Ability.MAPPINGS_MANAGE);
        Connection connection = connection(connectionName);
        Mapping mapping = store.mappingFor(connection.id(), object)
                .orElseThrow(() -> ApiException.notFound("This object has no mapping on this connection."));
        Version draft = store.draftOf(mapping.id())
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "There is no draft to publish."));
        if (draft.version() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        // Checked again against the fields the object has now: a field may have gone since the draft was saved.
        MappingCompiler.Result checked = compiler.compile(mapping.entity(), draft.definition(), mappable(object));
        if (!checked.ok()) {
            throw ApiException.validation(prefixed(checked.problems()));
        }
        requireScope(connection, object, checked.mapping());
        store.liveOf(mapping.id()).ifPresent(live -> store.supersede(live.id(), caller.userId()));
        if (!store.promote(draft.id(), version, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        record("published", caller, connection, mapping, draft.definition().fields().size());
        return view(mapping);
    }

    MappingView stop(String connectionName, String object) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW,
                Ability.MAPPINGS_MANAGE);
        Connection connection = connection(connectionName);
        Mapping mapping = store.mappingFor(connection.id(), object)
                .orElseThrow(() -> ApiException.notFound("This object has no mapping on this connection."));
        Version live = store.liveOf(mapping.id())
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "This mapping is not live."));
        store.supersede(live.id(), caller.userId());
        record("stopped", caller, connection, mapping, 0);
        return view(mapping);
    }

    /**
     * Tries a definition on a sample. The real mapper answers; nothing is stored, sent, logged or audited, and the
     * sample is not kept anywhere.
     */
    @Transactional(readOnly = true)
    MappingDryRunView dryRun(String connectionName, String object, MappingDryRunRequest request) {
        access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW, Ability.MAPPINGS_MANAGE);
        connection(connectionName);
        ObjectDefinition definition = mappable(object);
        MappingCompiler.Result checked = compiler.compile(request.entity(), request.definition(), definition);
        boolean outbound = "OUTBOUND".equals(request.direction());
        if (!outbound && !"INBOUND".equals(request.direction())) {
            throw ApiException.validation("direction", "Choose out or in.");
        }
        if (!checked.ok()) {
            return new MappingDryRunView(request.direction(), Map.of(), List.of(), prefixed(checked.problems()));
        }
        ExpressionEngine engine = compiler.engine();
        Map<String, List<String>> problems = new LinkedHashMap<>();
        if (outbound) {
            Map<String, Object> values = recordValues(definition, request.values(), engine, problems);
            if (!problems.isEmpty()) {
                return new MappingDryRunView("OUTBOUND", Map.of(), List.of(), problems);
            }
            Outbound out = mapper.out(checked.mapping(), values, null, clock.instant());
            if (out.failure() != null) {
                return new MappingDryRunView("OUTBOUND", Map.of(), List.of(),
                        Map.of("result", List.of(FlowReason.wordsFor(out.failure().reason()))));
            }
            Map<String, String> result = new LinkedHashMap<>();
            out.entity().fields().forEach((name, value) -> result.put(name, engine.formatValue(value)));
            return new MappingDryRunView("OUTBOUND", result, List.of(), Map.of());
        }
        Map<String, Object> raw = new HashMap<>(request.values());
        Inbound in = mapper.in(checked.mapping(), new CanonicalEntity(request.entity(), null, raw), null,
                clock.instant());
        if (in.failure() != null) {
            return new MappingDryRunView("INBOUND", Map.of(), List.of(),
                    Map.of("result", List.of(FlowReason.wordsFor(in.failure().reason()))));
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Assignment assignment : in.assignments()) {
            result.put(assignment.platform(), assignment.text());
        }
        List<MappingSkippedField> skipped = in.skipped().stream().map(item -> new MappingSkippedField(item.field(),
                item.reason(), FlowReason.wordsFor(item.reason()))).toList();
        return new MappingDryRunView("INBOUND", result, skipped, Map.of());
    }

    // ---- helpers ----

    /** The sample of a record, written as text, as the values the engine takes. */
    private static Map<String, Object> recordValues(ObjectDefinition object, Map<String, String> written,
            ExpressionEngine engine, Map<String, List<String>> problems) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, String> entry : written.entrySet()) {
            FieldDefinition field = object.field(entry.getKey()).orElse(null);
            if (field == null) {
                problems.put("values." + entry.getKey(), List.of("This object has no such field."));
                continue;
            }
            if (entry.getValue().length() > MAX_SAMPLE) {
                problems.put("values." + entry.getKey(), List.of("The value is too long."));
                continue;
            }
            ExpressionType type = MappingCompiler.typeOf(field);
            try {
                Object value = engine.parseValue(type, entry.getValue(), entry.getKey());
                if (value != null) {
                    values.put(entry.getKey(), value);
                }
            } catch (ExpressionException invalid) {
                problems.put("values." + entry.getKey(), List.of(invalid.problem()));
            }
        }
        return values;
    }

    private void requireScope(Connection connection, String object, CompiledMapping mapping) {
        IntegrationView.ScopeEntry scope = connection.definition().scope().stream()
                .filter(entry -> entry.object().equals(object)).findFirst().orElse(null);
        boolean out = mapping.fields().stream().anyMatch(field -> field.direction().out());
        boolean in = mapping.fields().stream().anyMatch(field -> field.direction().in());
        boolean readsOk = !out || scope != null && Boolean.TRUE.equals(scope.read());
        boolean writesOk = !in || scope != null && (mapping.inboundCreate() && Boolean.TRUE.equals(scope.create())
                || mapping.inboundUpdate() && Boolean.TRUE.equals(scope.update()));
        boolean ok = scope != null && readsOk && writesOk;
        if (!ok) {
            throw new ApiException(ErrorCode.CONFLICT, "The connection's scope does not allow what this mapping "
                    + "does with the object. Change the scope of the connection first.");
        }
    }

    private ObjectDefinition mappable(String object) {
        ObjectDefinition definition = metadata.object(object)
                .orElseThrow(() -> ApiException.notFound("This object does not exist."));
        if (definition.managedBy() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform and "
                    + "cannot be mapped.");
        }
        return definition;
    }

    private Connection connection(String name) {
        return connections.find(name).orElseThrow(() -> ApiException.notFound("This connection does not exist."));
    }

    private MappingView view(Mapping mapping) {
        List<Version> versions = store.versions(mapping.id());
        Version live = versions.stream().filter(version -> "LIVE".equals(version.status())).findFirst().orElse(null);
        Version draft = versions.stream().filter(Version::draft).findFirst().orElse(null);
        List<MappingVersionView> history = versions.stream().filter(version -> "SUPERSEDED".equals(version.status()))
                .limit(HISTORY).map(MappingService::versionView).toList();
        String connection = connections.findById(mapping.connectionId()).map(Connection::name).orElse("");
        return new MappingView(mapping.id(), connection, mapping.object(), mapping.entity(),
                live == null ? null : versionView(live), draft == null ? null : versionView(draft), history,
                draft != null);
    }

    private static MappingVersionView versionView(Version version) {
        return new MappingVersionView(version.id(), version.number(), version.status(), version.definition(),
                version.hash(), version.publishedAt(), version.version());
    }

    private String hash(MappingSpec spec) {
        return Mapper.sha256(json.writeValueAsString(spec));
    }

    private static Map<String, List<String>> prefixed(Map<String, List<String>> problems) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        problems.forEach((place, sentences) -> result.put("definition." + place, new ArrayList<>(sentences)));
        return result;
    }

    private void record(String action, TenantContext caller, Connection connection, Mapping mapping, int fields) {
        audit.record(AuditRecord.of("integration.mapping." + action, AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(caller.tenantId()).with("integration", connection.id().toString())
                .with("mapping", mapping.id().toString()).with("field_count", Integer.toString(fields)));
        meters.counter("platform.integration.mapping", "action", action).increment();
    }

}
