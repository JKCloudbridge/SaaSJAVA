package app.platform.security.internal;

import app.platform.security.GroupMembers;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Answers {@link GroupMembers} from the current rows (ADR-0047); no cache. */
@Service
class DefaultGroupMembers implements GroupMembers {

    private final GroupStore store;
    private final AccessWork work;

    DefaultGroupMembers(GroupStore store, AccessWork work) {
        this.store = store;
        this.work = work;
    }

    @Override
    public Set<UUID> groupsOf(UUID membershipId) {
        if (membershipId == null) {
            return Set.of();
        }
        return work.run(() -> Set.copyOf(store.groupsOfPerson(membershipId).keySet()));
    }

    @Override
    public Set<UUID> membersOf(UUID groupId) {
        if (groupId == null) {
            return Set.of();
        }
        return work.run(() -> Set.copyOf(store.peopleIn(groupId)));
    }
}
