package app.platform.security.internal;

import app.platform.security.Ability;
import app.platform.security.ObjectCatalog;
import app.platform.security.SharingCriterionChecker;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.ObjectSharingView;
import app.platformapi.QueryFilterNode;
import app.platformapi.SaveObjectSharingRequest;
import app.platformapi.SaveSharingRuleRequest;
import app.platformapi.SharingRuleView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The setup of record-level security (Sprint 17, ADR-0116): the organization-wide default of each object with the
 * hierarchy switch, and the sharing rules. Only whoever holds the ability to manage sharing reads or changes them; an
 * identifier of another organization is simply not found. A change is audited by names, identifiers and kinds, never by
 * the condition of a rule, and takes effect at the next question on every instance (the database raises the security
 * version in the same transaction, ADR-0053).
 *
 * <p>A condition is checked by the data module's own stages through {@link SharingCriterionChecker} (this module cannot
 * read metadata) and is stored as data. A rule names a role or a group, and the database keeps it from naming anything
 * else or from outliving them; this service answers in words first.
 */
@Service
class SharingService {

    static final String NO_OBJECT = "This object does not exist.";
    static final String FIXED = "The platform fixes who reaches the records of this object: the owner, people who "
            + "may view every record, and whoever a record is shared with.";

    private static final Set<Ability> MANAGERS = Set.of(Ability.SHARING_MANAGE);
    private static final Set<String> OWNER_SOURCES = Set.of("ROLE", "ROLE_AND_SUBORDINATES");
    private static final Set<String> TARGETS = Set.of("ROLE", "ROLE_AND_SUBORDINATES", "GROUP");
    private static final Set<String> LEVELS = Set.of("READ", "READ_WRITE");

    private final AccessGate gate;
    private final SharingStore store;
    private final AccessStore access;
    private final GroupStore groups;
    private final ObjectCatalog catalogue;
    private final ObjectProvider<SharingCriterionChecker> checker;
    private final CriterionCodec codec;
    private final AccessAudit audit;
    private final SecurityProperties properties;

    SharingService(AccessGate gate, SharingStore store, AccessStore access, GroupStore groups,
            ObjectCatalog catalogue, ObjectProvider<SharingCriterionChecker> checker, CriterionCodec codec,
            AccessAudit audit, SecurityProperties properties) {
        this.gate = gate;
        this.store = store;
        this.access = access;
        this.groups = groups;
        this.catalogue = catalogue;
        this.checker = checker;
        this.codec = codec;
        this.audit = audit;
        this.properties = properties;
    }

    // ---- defaults ----

    List<ObjectSharingView> objects() {
        return gate.run("sharing.objects", MANAGERS, caller -> {
            Map<String, SharingStore.SettingRow> configured = new LinkedHashMap<>();
            store.settings().forEach(row -> configured.put(row.object(), row));
            return catalogue.objects().stream().map(object -> view(object.key(), configured.get(object.key())))
                    .toList();
        });
    }

    ObjectSharingView object(String key) {
        return gate.run("sharing.object", MANAGERS, caller -> {
            requireObject(key);
            return view(key, store.setting(key).orElse(null));
        });
    }

    ObjectSharingView setObject(String key, SaveObjectSharingRequest request) {
        SharingDefault wanted = SharingDefault.ofKey(request.defaultAccess()).orElseThrow(() -> ApiException
                .validation("defaultAccess", "Choose PRIVATE, PUBLIC_READ or PUBLIC_READ_WRITE."));
        boolean hierarchy = Boolean.TRUE.equals(request.grantViaHierarchy());
        return gate.run("sharing.object.set", MANAGERS, caller -> {
            requireObject(key);
            if (catalogue.object(key).map(ObjectCatalog.ObjectInfo::sharingFixed).orElse(false)) {
                throw new ApiException(ErrorCode.CONFLICT, FIXED);
            }
            ActorId actor = new ActorId(caller.userId());
            Optional<SharingStore.SettingRow> current = store.settingForUpdate(key);
            String from = current.map(SharingStore.SettingRow::defaultAccess)
                    .orElse(SharingDefault.PUBLIC_READ_WRITE.key());
            if (current.isEmpty()) {
                try {
                    store.insertSetting(key, wanted.key(), hierarchy, actor);
                } catch (DuplicateKeyException e) {
                    throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            } else {
                if (request.version() == null) {
                    throw ApiException.validation("version", "Send the version that was read.");
                }
                if (!store.updateSetting(key, request.version(), wanted.key(), hierarchy, actor)) {
                    throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            }
            audit.sharingDefaultSet(caller.userId(), key, from, wanted.key(), hierarchy);
            return view(key, store.setting(key).orElseThrow());
        });
    }

    // ---- rules ----

    List<SharingRuleView> rules(String object) {
        return gate.run("sharing.rules", MANAGERS, caller -> {
            if (object != null) {
                requireObject(object);
            }
            List<SharingStore.RuleRow> rows = object == null ? store.allRules() : store.rules(object);
            return rows.stream().map(this::view).toList();
        });
    }

    SharingRuleView rule(UUID id) {
        return gate.run("sharing.rule", MANAGERS, caller -> view(existing(id)));
    }

    SharingRuleView createRule(SaveSharingRuleRequest request) {
        return gate.run("sharing.rule.create", MANAGERS, caller -> {
            String object = request.object();
            if (object == null || object.isBlank()) {
                throw ApiException.validation("object", "Name the object the rule is for.");
            }
            requireObject(object);
            access.lockAccessChanges();
            if (store.countRules(object) >= properties.records().maxRulesPerObject()) {
                throw new ApiException(ErrorCode.CONFLICT, "This object has the most sharing rules it can have.");
            }
            String kind = request.kind() == null ? "" : request.kind();
            SharingStore.RuleRow checked = checked(object, kind, request, null);
            UUID id;
            try {
                id = store.insertRule(checked, new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another rule of this object.");
            }
            audit.sharingRuleChanged(caller.userId(), "created", id, object, kind);
            return view(existing(id));
        });
    }

    SharingRuleView updateRule(UUID id, SaveSharingRuleRequest request) {
        return gate.run("sharing.rule.update", MANAGERS, caller -> {
            access.lockAccessChanges();
            SharingStore.RuleRow current = existing(id);
            if (request.version() == null) {
                throw ApiException.validation("version", "Send the version that was read.");
            }
            SharingStore.RuleRow checked = checked(current.object(), current.kind(), request, id);
            try {
                if (!store.updateRule(checked, request.version(), new ActorId(caller.userId()))) {
                    throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another rule of this object.");
            }
            audit.sharingRuleChanged(caller.userId(), "updated", id, current.object(), current.kind());
            return view(existing(id));
        });
    }

    void deleteRule(UUID id) {
        gate.run("sharing.rule.delete", MANAGERS, caller -> {
            SharingStore.RuleRow current = existing(id);
            store.deleteRule(id, new ActorId(caller.userId()));
            audit.sharingRuleChanged(caller.userId(), "deleted", id, current.object(), current.kind());
            return null;
        });
    }

    // ---- the checks ----

    /** The rule as it will be stored, or the problems of the request, all at once with their places. */
    private SharingStore.RuleRow checked(String object, String kind, SaveSharingRuleRequest request, UUID id) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        String name = null;
        try {
            name = Names.name(request.name());
        } catch (ApiException e) {
            problems.putAll(e.fields());
        }
        String sourceType = request.sourceType();
        UUID sourceId = request.sourceId();
        String criterion = null;
        switch (kind) {
            case "OWNER" -> {
                if (sourceType == null || !OWNER_SOURCES.contains(sourceType)) {
                    add(problems, "sourceType", "Choose ROLE or ROLE_AND_SUBORDINATES.");
                } else if (sourceId == null || access.role(sourceId).isEmpty()) {
                    add(problems, "sourceId", "Is not a role of this organization.");
                }
                requireNoCriterion(problems, request);
            }
            case "GROUP" -> {
                sourceType = "GROUP";
                if (sourceId == null || groups.group(sourceId).isEmpty()) {
                    add(problems, "sourceId", "Is not a public group of this organization.");
                }
                requireNoCriterion(problems, request);
            }
            case "CRITERIA" -> {
                sourceType = null;
                if (sourceId != null || request.sourceType() != null) {
                    add(problems, "sourceId", "A rule by condition has no source; leave it out.");
                    sourceId = null;
                }
                criterion = checkedCriterion(problems, object, request.criterion());
            }
            default -> add(problems, "kind", "Choose OWNER, GROUP or CRITERIA.");
        }
        if (request.targetType() == null || !TARGETS.contains(request.targetType())) {
            add(problems, "targetType", "Choose ROLE, ROLE_AND_SUBORDINATES or GROUP.");
        } else if (!targetExists(request.targetType(), request.targetId())) {
            add(problems, "targetId", "GROUP".equals(request.targetType())
                    ? "Is not a public group of this organization." : "Is not a role of this organization.");
        }
        if (request.accessLevel() == null || !LEVELS.contains(request.accessLevel())) {
            add(problems, "accessLevel", "Choose READ or READ_WRITE.");
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        boolean active = request.active() == null || request.active();
        return new SharingStore.RuleRow(id, object, name, kind, sourceType, sourceId, criterion,
                request.targetType(), request.targetId(), request.accessLevel(), active, 0);
    }

    private String checkedCriterion(Map<String, List<String>> problems, String object, QueryFilterNode criterion) {
        if (criterion == null) {
            add(problems, "criterion", "A rule by condition needs a condition.");
            return null;
        }
        SharingCriterionChecker found = checker.getIfAvailable();
        if (found == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        for (SharingCriterionChecker.Problem problem : found.check(object, criterion)) {
            add(problems, problem.place(), problem.message());
        }
        return problems.isEmpty() ? codec.write(criterion) : null;
    }

    private static void requireNoCriterion(Map<String, List<String>> problems, SaveSharingRuleRequest request) {
        if (request.criterion() != null) {
            add(problems, "criterion", "Only a rule by condition has a condition; leave it out.");
        }
    }

    private boolean targetExists(String type, UUID id) {
        if (id == null) {
            return false;
        }
        return "GROUP".equals(type) ? groups.group(id).isPresent() : access.role(id).isPresent();
    }

    private static void add(Map<String, List<String>> problems, String place, String message) {
        problems.computeIfAbsent(place, key -> new ArrayList<>()).add(message);
    }

    private void requireObject(String key) {
        if (key == null || catalogue.object(key).isEmpty()) {
            throw ApiException.notFound(NO_OBJECT);
        }
    }

    private SharingStore.RuleRow existing(UUID id) {
        return store.rule(id).orElseThrow(() -> ApiException.notFound("This sharing rule does not exist."));
    }

    // ---- views ----

    private ObjectSharingView view(String key, SharingStore.SettingRow row) {
        if (catalogue.object(key).map(ObjectCatalog.ObjectInfo::sharingFixed).orElse(false)) {
            // What the platform fixed, whatever a row says (the record security ignores the row too).
            return new ObjectSharingView(key, SharingDefault.PRIVATE.key(), false, false, 0, true);
        }
        if (row == null) {
            return new ObjectSharingView(key, SharingDefault.PUBLIC_READ_WRITE.key(), true, false, 0, false);
        }
        return new ObjectSharingView(key, row.defaultAccess(), row.grantViaHierarchy(), true, row.version(), false);
    }

    private SharingRuleView view(SharingStore.RuleRow row) {
        QueryFilterNode criterion = row.criterion() == null ? null : codec.read(row.criterion());
        return new SharingRuleView(row.id(), row.object(), row.name(), row.kind(), row.sourceType(), row.sourceId(),
                criterion, row.targetType(), row.targetId(), row.accessLevel(), row.active(), row.version());
    }
}
