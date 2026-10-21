package app.platform.security.internal;

import app.platform.security.ShareTargets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Answers {@link ShareTargets} from the current rows (ADR-0185). The roles and the groups of an organization are a few
 * dozen at most (the setup screens list them whole), so the search is done on the names read, and the limit bounds what
 * is handed on. Nothing is cached: a role renamed or removed is seen by the next question.
 */
@Service
class DefaultShareTargets implements ShareTargets {

    private final AccessStore roles;
    private final GroupStore groups;
    private final AccessWork work;

    DefaultShareTargets(AccessStore roles, GroupStore groups, AccessWork work) {
        this.roles = roles;
        this.groups = groups;
        this.work = work;
    }

    @Override
    public boolean roleExists(UUID roleId) {
        return roleId != null && work.run(() -> roles.role(roleId).isPresent());
    }

    @Override
    public List<Target> roles(String search, int limit) {
        return work.run(() -> found(roles.roles().stream().map(role -> new Target(role.id(), role.name())).toList(),
                search, limit));
    }

    @Override
    public List<Target> groups(String search, int limit) {
        return work.run(() -> found(groups.groups().stream()
                .map(group -> new Target(group.id(), group.name())).toList(), search, limit));
    }

    @Override
    public Map<UUID, String> roleLabels(Collection<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> wanted = Set.copyOf(roleIds);
        return work.run(() -> roles.roles().stream().filter(role -> wanted.contains(role.id()))
                .collect(Collectors.toMap(AccessStore.RoleRow::id, AccessStore.RoleRow::name)));
    }

    @Override
    public Map<UUID, String> groupLabels(Collection<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> wanted = Set.copyOf(groupIds);
        return work.run(() -> groups.groups().stream().filter(group -> wanted.contains(group.id()))
                .collect(Collectors.toMap(GroupStore.GroupRow::id, GroupStore.GroupRow::name,
                        (first, second) -> first, LinkedHashMap::new)));
    }

    private static List<Target> found(List<Target> all, String search, int limit) {
        String text = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        return all.stream().filter(target -> text.isEmpty()
                        || target.label().toLowerCase(Locale.ROOT).contains(text))
                .limit(Math.max(limit, 0)).toList();
    }
}
