package app.platform.security.internal;

import app.platform.security.DataAccess;
import app.platform.security.GroupMembers;
import app.platform.security.ObjectAction;
import app.platform.security.ObjectCatalog;
import app.platform.security.Permissions;
import app.platform.security.RecordAccess;
import app.platform.security.RecordSecurity;
import app.platform.security.ShareLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads what record-level security needs and hands it to {@link RecordAccessResolver} (Sprint 17, ADR-0115). The
 * answer is kept in the {@link SecurityCache} under the organization's security version: the defaults, the rules, the
 * role tree, the members' roles, the groups, the permissions and the memberships all carry the version trigger, so a
 * change of any of them is the answer of the next question on every instance. The explicit shares of single records are
 * not read here (the statement reads them), which is why sharing a record does not touch the version.
 *
 * <p>The answer is never computed for a member who cannot read the object, and the work of reading the hierarchy, the
 * groups and the rules is skipped when the answer is "every record" whatever they say (a default of Public
 * Read/Write, or "modify all").
 */
@Service
class DefaultRecordSecurity implements RecordSecurity {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRecordSecurity.class);

    private final Permissions permissions;
    private final ObjectCatalog catalogue;
    private final SharingStore store;
    private final GroupMembers groups;
    private final AccessWork work;
    private final SecurityVersions versions;
    private final SecurityCache cache;
    private final SecurityProperties properties;
    private final CriterionCodec codec;
    private final Counter truncated;

    DefaultRecordSecurity(Permissions permissions, ObjectCatalog catalogue, SharingStore store, GroupMembers groups,
            AccessWork work, SecurityVersions versions, SecurityCache cache, SecurityProperties properties,
            CriterionCodec codec, MeterRegistry meters) {
        this.permissions = permissions;
        this.catalogue = catalogue;
        this.store = store;
        this.groups = groups;
        this.work = work;
        this.versions = versions;
        this.cache = cache;
        this.properties = properties;
        this.codec = codec;
        this.truncated = Counter.builder("platform.security.records.truncated").register(meters);
    }

    @Override
    public RecordAccess accessTo(UUID membershipId, String object) {
        if (membershipId == null || object == null) {
            return RecordAccess.nothing(object);
        }
        return work.run(() -> cache.get(
                new SecurityCache.Key(work.tenant(), membershipId, SecurityCache.Kind.RECORD_ACCESS, object),
                versions.read(), () -> compute(membershipId, object)));
    }

    private RecordAccess compute(UUID membershipId, String object) {
        Optional<ObjectCatalog.ObjectInfo> info = catalogue.object(object);
        if (info.isEmpty()) {
            return RecordAccess.nothing(object);
        }
        boolean fixed = info.get().sharingFixed();
        DataAccess data = permissions.data(membershipId);
        boolean canRead = data.allows(object, ObjectAction.READ);
        if (!canRead) {
            return RecordAccess.nothing(object);
        }
        Optional<SharingStore.Viewer> viewer = store.viewer(membershipId);
        if (viewer.isEmpty()) {
            return RecordAccess.nothing(object);
        }
        boolean modifyAll = data.allows(object, ObjectAction.MODIFY_ALL);
        boolean viewAll = data.allows(object, ObjectAction.VIEW_ALL);
        // An object whose default the platform fixes (FA4, ADR-0189) is private with no hierarchy whatever a row says:
        // the row is not even read, so one that got in some other way changes nothing.
        Optional<SharingStore.SettingRow> setting = fixed ? Optional.empty() : store.setting(object);
        // A default this release does not know is treated as the strictest, never as the loosest.
        SharingDefault defaultAccess = fixed ? SharingDefault.PRIVATE : setting.map(row -> SharingDefault
                .ofKey(row.defaultAccess()).orElse(SharingDefault.PRIVATE)).orElse(SharingDefault.PUBLIC_READ_WRITE);
        boolean hierarchy = !fixed && setting.map(SharingStore.SettingRow::grantViaHierarchy).orElse(true);
        UUID user = viewer.get().userId();

        if (modifyAll || defaultAccess == SharingDefault.PUBLIC_READ_WRITE) {
            return RecordAccessResolver.resolve(new RecordAccessResolver.Facts(object, true, viewAll, modifyAll,
                    defaultAccess, hierarchy, user, Set.of(), null, Set.of(), Set.of(), List.of(),
                    properties.records().maxOwners()));
        }
        Set<UUID> memberGroups = groups.groupsOf(membershipId);
        UUID role = viewer.get().roleId();
        // The member's role and the roles above it: asked once, for the shares to "a role and the roles below it" and
        // for the sharing rules aimed at a role and its subordinates.
        Set<UUID> roleChain = role == null ? Set.of() : store.rolesAboveOrSelf(role);
        // The hierarchy only widens what is read; when every record is read anyway there is nobody to look up.
        boolean readsAll = viewAll || defaultAccess != SharingDefault.PRIVATE;
        Set<UUID> subordinates = hierarchy && role != null && !readsAll ? store.usersBelow(role) : Set.of();
        List<RecordAccessResolver.Rule> rules = rulesReaching(object, role, roleChain, memberGroups);
        RecordAccess access = RecordAccessResolver.resolve(new RecordAccessResolver.Facts(object, true, viewAll,
                false, defaultAccess, hierarchy, user, memberGroups, role, roleChain, subordinates, rules,
                properties.records().maxOwners()));
        if (access.truncated()) {
            truncated.increment();
            LOG.warn("A description of what a member may reach was cut at the bound of owners (object {}); raise "
                    + "platform.security.records.max-owners or use a group-based sharing rule", object);
        }
        return access;
    }

    /** The active rules of the object that reach a member with this role and these groups, with their people. */
    private List<RecordAccessResolver.Rule> rulesReaching(String object, UUID role, Set<UUID> rolesAbove,
            Set<UUID> memberGroups) {
        List<SharingStore.RuleRow> candidates = store.activeRules(object);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RecordAccessResolver.Rule> reaching = new ArrayList<>();
        for (SharingStore.RuleRow rule : candidates) {
            if (!reaches(rule, role, rolesAbove, memberGroups)) {
                continue;
            }
            ShareLevel level = "READ_WRITE".equals(rule.accessLevel()) ? ShareLevel.READ_WRITE : ShareLevel.READ;
            switch (rule.kind()) {
                case "OWNER" -> reaching.add(RecordAccessResolver.Rule.ofOwners(rule.id(), level,
                        "ROLE".equals(rule.sourceType()) ? store.usersInRole(rule.sourceId())
                                : store.usersInSubtree(rule.sourceId())));
                case "GROUP" -> reaching.add(RecordAccessResolver.Rule.ofOwners(rule.id(), level,
                        store.usersInGroup(rule.sourceId())));
                case "CRITERIA" -> {
                    try {
                        reaching.add(RecordAccessResolver.Rule.ofCriterion(rule.id(), level,
                                codec.read(rule.criterion())));
                    } catch (RuntimeException e) {
                        // A stored condition this release cannot read gives nothing: it fails closed. The text is not
                        // logged (it belongs to the organization).
                        LOG.warn("A sharing rule's condition could not be read and gives no access (rule {})",
                                rule.id());
                    }
                }
                default -> LOG.warn("A sharing rule of an unknown kind gives no access (rule {})", rule.id());
            }
        }
        return reaching;
    }

    private static boolean reaches(SharingStore.RuleRow rule, UUID role, Set<UUID> rolesAbove,
            Set<UUID> memberGroups) {
        return switch (rule.targetType()) {
            case "GROUP" -> memberGroups.contains(rule.targetId());
            case "ROLE" -> role != null && role.equals(rule.targetId());
            case "ROLE_AND_SUBORDINATES" -> rolesAbove.contains(rule.targetId());
            default -> false;
        };
    }
}
