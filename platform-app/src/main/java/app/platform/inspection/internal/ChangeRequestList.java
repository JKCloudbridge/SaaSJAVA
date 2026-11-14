package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.RecordTitles;
import app.platform.inspection.internal.ChangeRequestStore.ListFilter;
import app.platform.inspection.internal.ChangeRequestStore.Order;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestPage;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestQuery;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestRow;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platformapi.ApiException;
import app.platformapi.Cursors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The list of change requests across templates (FA6E, ADR-0199): the page behind the tab of the application, the way
 * the list of fill-ins is the page behind its tab.
 *
 * <p><strong>Who sees which request is not decided here.</strong> It is the rule of {@link ChangeRequestAccess}, asked
 * for each template of a batch: a person reaches the requests of a template only while they can read the template,
 * sees their own, and sees all of them when they may change the template. A person who may change no template at all
 * gets a query narrowed to their own requests, which is the cheap and common case; for anyone else a batch is read,
 * judged, and the next batch is read until the page is full or a bound is reached, so a page may be short and still
 * have a cursor. Only a missing cursor is the end.
 *
 * <p><strong>Typed text.</strong> The search text is matched against titles and the names of the people who asked and
 * is never logged, audited or put in an error; a person's name is shown only as the API already shows it.
 */
@Service
@Transactional(readOnly = true)
class ChangeRequestList {

    private static final int DEFAULT_SIZE = 50;
    private static final int MOST = 200;
    /** How many rows are read and judged at a time. */
    private static final int BATCH = 100;
    /** The most rows one call reads and judges, so a person who sees few of many requests is not made to wait. */
    private static final int MOST_SCANNED = 2000;
    /** The most people whose names a search matches. */
    private static final int MOST_PEOPLE = 2000;

    private final ChangeRequestAccess access;
    private final ChangeRequestStore store;
    private final RecordTitles titles;
    private final BackgroundRecords records;
    private final List<ReferenceLabels> labels;

    ChangeRequestList(ChangeRequestAccess access, ChangeRequestStore store, RecordTitles titles,
            BackgroundRecords records, List<ReferenceLabels> labels) {
        this.access = access;
        this.store = store;
        this.titles = titles;
        this.records = records;
        this.labels = labels;
    }

    /** One page of the requests the caller may see, in the order asked. */
    ChangeRequestPage query(RecordSet set, ChangeRequestQuery query) {
        TemplateAccess.Caller caller = access.member();
        Order order = orderOf(query.sort());
        boolean descending = query.descending() != null ? query.descending() : order == Order.DATE;
        int size = query.size() == null || query.size() <= 0 ? DEFAULT_SIZE : Math.min(query.size(), MOST);
        UUID only = access.mayReviewSome(set, caller.membershipId()) ? null : caller.userId();
        String text = query.search() == null ? "" : query.search().strip();
        String like = text.isEmpty() ? null : "%" + escape(text) + "%";
        Set<UUID> people = text.isEmpty() ? Set.of() : peopleNamed(set, text, only);
        String status = query.status() == null || query.status().isBlank() ? null : query.status();

        String[] position = decode(query.cursor(), order);
        Judge judge = new Judge(set, caller);
        List<RequestRow> kept = new ArrayList<>();
        RequestRow last = null;
        boolean exhausted = false;
        int scanned = 0;
        while (kept.size() < size && scanned < MOST_SCANNED && !exhausted) {
            ListFilter filter = new ListFilter(only, like, people, status, query.templateId(), order, descending,
                    position == null ? null : position[0], position == null ? null : UUID.fromString(position[1]),
                    set.template());
            List<RequestRow> batch = store.page(filter, BATCH);
            for (RequestRow row : batch) {
                scanned++;
                last = row;
                if (judge.sees(row)) {
                    kept.add(row);
                    if (kept.size() == size) {
                        break;
                    }
                }
            }
            if (batch.size() < BATCH && kept.size() < size) {
                exhausted = true;
            }
            if (last != null) {
                position = new String[] {ChangeRequestStore.orderValue(last, order), last.id().toString()};
            }
        }
        // The cursor is the last row looked at, kept or not: nothing is looked at twice and nothing is skipped.
        boolean more = !exhausted && last != null;
        String cursor = more ? Cursors.encode(order.name() + "|" + position[0] + "|" + position[1]) : null;
        return new ChangeRequestPage(rows(caller, kept, judge), cursor);
    }

    // ---- the pieces ----

    private List<ChangeRequestRow> rows(TemplateAccess.Caller caller, List<RequestRow> kept, Judge judge) {
        Set<UUID> askers = new HashSet<>();
        kept.forEach(row -> askers.add(row.createdBy()));
        Map<UUID, String> names = nameOf(askers);
        List<ChangeRequestRow> rows = new ArrayList<>();
        for (RequestRow row : kept) {
            Seen template = judge.template(row.templateId());
            rows.add(new ChangeRequestRow(row.id(), row.templateId(), template.name(), template.number(), row.title(),
                    row.status(), row.versionNo(), names.get(row.createdBy()), row.requesterDepartment(),
                    row.createdBy().equals(caller.userId()), row.createdAt(), row.headerId()));
        }
        return rows;
    }

    /** What the caller may see of a template, read once for the call. */
    private record Seen(boolean readable, boolean review, String name, String number) {
    }

    /** Judges the rows of a call by the rule of {@link ChangeRequestAccess}, remembering each template. */
    private final class Judge {

        private final RecordSet set;
        private final TemplateAccess.Caller caller;
        private final Map<UUID, Seen> templates = new HashMap<>();

        private Judge(RecordSet set, TemplateAccess.Caller caller) {
            this.set = set;
            this.caller = caller;
        }

        boolean sees(RequestRow row) {
            Seen seen = template(row.templateId());
            return seen.readable() && (seen.review() || row.createdBy().equals(caller.userId()));
        }

        Seen template(UUID templateId) {
            return templates.computeIfAbsent(templateId, id -> {
                if (!titles.titlesOf(set.template(), List.of(id)).containsKey(id)) {
                    return new Seen(false, false, null, null);
                }
                DataRecord found = records.find(set.template(), id).orElse(null);
                // An application narrowed to its own documents never shows a request on another one's (ADR-0259).
                if (found != null && !set.admits(found.fields())) {
                    return new Seen(false, false, null, null);
                }
                return new Seen(true, access.canReview(set, caller.membershipId(), id), found == null ? null
                        : text(found, Templates.NAME), found == null ? null : text(found, Templates.NUMBER));
            });
        }
    }

    private Set<UUID> peopleNamed(RecordSet set, String text, UUID only) {
        Set<UUID> askers = new LinkedHashSet<>(store.requesters(set.template(), only, MOST_PEOPLE));
        String wanted = text.toLowerCase(Locale.ROOT);
        Set<UUID> matching = new LinkedHashSet<>();
        nameOf(askers).forEach((id, name) -> {
            if (name != null && name.toLowerCase(Locale.ROOT).contains(wanted)) {
                matching.add(id);
            }
        });
        return matching;
    }

    /** The display names of the people, for the active members among them; a person who left has none. */
    private Map<UUID, String> nameOf(Set<UUID> people) {
        if (people.isEmpty()) {
            return Map.of();
        }
        Optional<ReferenceLabels> users = labels.stream().filter(one -> "User".equals(one.object())).findFirst();
        return users.map(one -> one.labels(people)).orElse(Map.of());
    }

    private static Order orderOf(String sort) {
        if (sort == null || sort.isBlank() || "date".equals(sort)) {
            return Order.DATE;
        }
        return switch (sort) {
            case "version" -> Order.VERSION;
            case "status" -> Order.STATUS;
            default -> throw ApiException.validation("sort", "Choose date, version or status.");
        };
    }

    /** The key of the last row of the previous page of this same question, or null for the first page. */
    private static String[] decode(String cursor, Order order) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = Cursors.decode(cursor).map(text -> text.split("\\|")).orElse(new String[0]);
        if (parts.length != 3 || !parts[0].equals(order.name())) {
            throw ApiException.validation("cursor", "This is not a cursor of this list.");
        }
        try {
            UUID.fromString(parts[2]);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("cursor", "This is not a cursor of this list.");
        }
        return new String[] {parts[1], parts[2]};
    }

    /** Makes the text safe inside a like pattern whose escape character is the exclamation mark. */
    private static String escape(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String text(DataRecord record, String field) {
        Object value = record.fields().get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
