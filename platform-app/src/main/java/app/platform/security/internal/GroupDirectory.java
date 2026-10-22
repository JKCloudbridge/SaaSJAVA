package app.platform.security.internal;

import app.platform.security.ShareTargets;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.sharedkernel.directory.ReferenceLabels;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers the record store's questions about the {@code Group} object (FA4, ADR-0190): is this identifier a live public
 * group of the current organization, and what is it called. It lets a lookup field of an organization's record or of a
 * shipped object point at a group without the record store reading the groups' tables, the same arrangement as the
 * directory of users.
 *
 * <p>A group that does not exist, one of another organization and one that was removed all answer the same ("no", no
 * name). The names of groups are the organization's setup and not secret to its members, as for {@link ShareTargets};
 * the people in a group are never asked here. The search is for a picker, bounded, and matches part of a name.
 */
@Component
class GroupDirectory implements ReferenceDirectory, ReferenceLabels {

    private final ShareTargets targets;

    GroupDirectory(ShareTargets targets) {
        this.targets = targets;
    }

    @Override
    public String object() {
        return "Group";
    }

    @Override
    public boolean exists(UUID identifier) {
        return identifier != null && !targets.groupLabels(List.of(identifier)).isEmpty();
    }

    @Override
    public Map<UUID, String> labels(Collection<UUID> identifiers) {
        return targets.groupLabels(identifiers);
    }

    @Override
    public List<Option> options(String text, int limit) {
        return targets.groups(text, limit).stream().map(target -> new Option(target.id(), target.label())).toList();
    }
}
