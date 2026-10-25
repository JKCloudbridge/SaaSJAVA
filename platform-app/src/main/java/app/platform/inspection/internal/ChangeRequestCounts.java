package app.platform.inspection.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How many change requests of a template still wait for someone, as far as the caller may see them (FA6E, ADR-0199):
 * the number behind the asterisk of the template list and of the template page. It is a reading aid and decides
 * nothing. The rule of who sees a request is {@link ChangeRequestAccess}'s, so a person who may change the template
 * counts every request and anyone else counts their own; a template that has none costs one statement for the whole
 * page, and only a template that has some is judged.
 */
@Component
class ChangeRequestCounts {

    private final ChangeRequestStore store;
    private final ChangeRequestAccess access;

    ChangeRequestCounts(ChangeRequestStore store, ChangeRequestAccess access) {
        this.store = store;
        this.access = access;
    }

    /**
     * The counts of these templates; a template with none is not in the map.
     *
     * @param caller the member who asks
     * @param templateIds the templates of a page
     */
    Map<UUID, Integer> waiting(TemplateAccess.Caller caller, Collection<UUID> templateIds) {
        Map<UUID, Integer> all = store.toActCounts(templateIds, null);
        if (all.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> visible = new HashMap<>();
        List<UUID> notReviewed = all.keySet().stream()
                .filter(id -> !access.canReview(caller.membershipId(), id)).toList();
        all.forEach((id, count) -> {
            if (!notReviewed.contains(id)) {
                visible.put(id, count);
            }
        });
        if (!notReviewed.isEmpty()) {
            store.toActCounts(notReviewed, caller.userId()).forEach(visible::put);
        }
        return visible;
    }
}
