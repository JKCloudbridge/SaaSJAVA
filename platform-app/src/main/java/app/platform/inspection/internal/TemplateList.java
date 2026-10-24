package app.platform.inspection.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryFilterNodes;
import app.platform.data.QueryResult;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.inspection.internal.TemplateViews.TemplatePage;
import app.platform.inspection.internal.TemplateViews.TemplateRow;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.sheets.SheetLifecycle;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The master list of templates (FA4, ADR-0188). A page of header records comes from the record store, so everything it
 * decides about who sees what applies unchanged (the object permission, the private default of a template, the shares,
 * the fields the caller may read); the lifecycle of the templates on the page then comes from one statement over the
 * versions of exactly those templates, which never reads a grid. Two people asking the same question see different
 * lists because the first step differs, not because this class knows who they are.
 *
 * <p><strong>Cost.</strong> The page is one indexed or bounded query on the headers plus one grouped read over the
 * version table for at most a page of owners; neither grows with the number of versions or the size of their grids.
 *
 * <p><strong>The lifecycle filter</strong> cannot be part of the header query (a header holds no pointer to a version,
 * ADR-0174 section 1), so it is applied to the page after it is read: a page can then hold fewer rows than asked while
 * the cursor still goes on. The page says so ({@code filtered}).
 */
@Service
@Transactional(readOnly = true)
class TemplateList {

    private static final int DEFAULT_SIZE = 50;
    private static final int MOST = 200;
    private static final Set<String> STATES = Set.of("ACTIVE", "APPROVED", "IN_REVIEW", "DRAFT", "NONE", "RETIRED");
    private static final List<String> FIELDS = List.of(Templates.NAME, Templates.NUMBER, Templates.CATEGORY,
            Templates.SUB_CATEGORY, Templates.LEVEL, Templates.DOCUMENT_TYPE, Templates.RETIRED);

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,10}");

    private final TemplateAccess access;
    private final Records records;
    private final SheetLifecycle sheets;
    private final Decisions decisions;

    TemplateList(TemplateAccess access, Records records, SheetLifecycle sheets, Decisions decisions) {
        this.access = access;
        this.records = records;
        this.sheets = sheets;
        this.decisions = decisions;
    }

    /**
     * One page of the templates the caller may see.
     *
     * @param search part of a name or of a number, or null
     * @param category a category code, or null
     * @param subCategory a sub-category code, or null
     * @param retired true for retired templates only, false for the others, null for all
     * @param state a lifecycle state ({@code ACTIVE}, {@code APPROVED}, {@code IN_REVIEW}, {@code DRAFT},
     *        {@code NONE}, {@code RETIRED}), or null
     * @param sort {@code name}, {@code number} or {@code newest}; null is {@code name}
     * @param size the page size, at most 200; 0 or null is 50
     * @param cursor the cursor of the previous page of the same question, or null
     */
    TemplatePage page(String search, String category, String subCategory, Boolean retired, String state, String sort,
            Integer size, String cursor) {
        return run(search, null, category, subCategory, retired, state, order(sort), size, cursor);
    }

    /**
     * One page of the templates the caller may see for a question a person built (UI3A): a search text, the filter
     * and the order of a list view, and a lifecycle state. The filter and the order are the closed query model of
     * Sprint 16, checked by the same stages as any list of records, so a field the caller may not read is a field
     * that does not exist and a record they may not see is never in the answer.
     *
     * @param query the question; its filter, sort, state, size and cursor may all be null
     */
    TemplatePage query(TemplateViews.TemplateQuery query) {
        QueryFilter filter = query.filter() == null ? null : QueryFilterNodes.toFilter(query.filter(), "filter");
        List<QuerySort> sort = query.sort() == null ? List.of() : query.sort().stream()
                .map(key -> new QuerySort(key.field(), Boolean.TRUE.equals(key.descending()))).toList();
        return run(query.search(), filter, null, null, null, query.state(), sort.isEmpty() ? order(null) : sort,
                query.size(), query.cursor());
    }

    private TemplatePage run(String search, QueryFilter extra, String category, String subCategory, Boolean retired,
            String state, List<QuerySort> sort, Integer size, String cursor) {
        Caller caller = access.member();
        String wantedState = state == null || state.isBlank() ? null : state.toUpperCase(Locale.ROOT);
        if (wantedState != null && !STATES.contains(wantedState)) {
            throw ApiException.validation("state", "Choose ACTIVE, APPROVED, IN_REVIEW, DRAFT, NONE or RETIRED.");
        }
        int limit = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MOST);
        RecordQuery query = new RecordQuery(filter(search, extra, category, subCategory, retired), sort, FIELDS,
                List.of(), limit, cursor);
        QueryResult result = records.query(Templates.OBJECT, query);
        List<UUID> ids = result.records().stream().map(QueriedRecord::record).map(DataRecord::id).toList();
        Map<UUID, SheetLifecycle.Summary> summaries = sheets.summaries(Templates.OBJECT, ids);
        List<TemplateRow> rows = new ArrayList<>();
        for (QueriedRecord found : result.records()) {
            DataRecord header = found.record();
            boolean isRetired = Boolean.TRUE.equals(header.fields().get(Templates.RETIRED));
            SheetLifecycle.Summary summary = summaries.getOrDefault(header.id(),
                    new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0));
            var lifecycle = TemplatePublishing.lifecycle(summary, isRetired);
            if (wantedState == null || wantedState.equals(lifecycle.state())) {
                rows.add(new TemplateRow(header.id(), text(header, Templates.NAME), text(header, Templates.NUMBER),
                        text(header, Templates.CATEGORY), text(header, Templates.SUB_CATEGORY),
                        text(header, Templates.LEVEL), text(header, Templates.DOCUMENT_TYPE), isRetired, lifecycle));
            }
        }
        boolean canCreate = decisions.can(caller.membershipId(), Templates.OBJECT, ObjectAction.CREATE).allowed();
        return new TemplatePage(rows, result.nextCursor(), canCreate, wantedState != null);
    }

    private static QueryFilter filter(String search, QueryFilter extra, String category, String subCategory,
            Boolean retired) {
        List<QueryFilter> all = new ArrayList<>();
        if (extra != null) {
            all.add(extra);
        }
        if (search != null && !search.isBlank()) {
            String text = search.strip();
            if (text.length() > 80) {
                throw ApiException.validation("search", "Is too long.");
            }
            List<QueryFilter> any = new ArrayList<>(List.of(
                    condition(QueryFilter.Op.CONTAINS, Templates.NAME, text),
                    condition(QueryFilter.Op.CONTAINS, Templates.NUMBER, text)));
            if (CODE.matcher(text).matches()) {
                // The category and the sub-category are picklists of short codes: a code typed whole finds them.
                any.add(condition(QueryFilter.Op.EQ, Templates.CATEGORY, text.toUpperCase(Locale.ROOT)));
                any.add(condition(QueryFilter.Op.EQ, Templates.SUB_CATEGORY, text.toUpperCase(Locale.ROOT)));
            }
            all.add(new QueryFilter(QueryFilter.Op.OR, any, null, null));
        }
        if (category != null && !category.isBlank()) {
            all.add(condition(QueryFilter.Op.EQ, Templates.CATEGORY, category.strip()));
        }
        if (subCategory != null && !subCategory.isBlank()) {
            all.add(condition(QueryFilter.Op.EQ, Templates.SUB_CATEGORY, subCategory.strip()));
        }
        if (Boolean.TRUE.equals(retired)) {
            all.add(condition(QueryFilter.Op.EQ, Templates.RETIRED, true));
        } else if (Boolean.FALSE.equals(retired)) {
            // A template never retired holds no value at all, so "not retired" is "no value or false".
            all.add(new QueryFilter(QueryFilter.Op.OR, List.of(
                    new QueryFilter(QueryFilter.Op.IS_EMPTY, null, Templates.RETIRED, null),
                    condition(QueryFilter.Op.EQ, Templates.RETIRED, false)), null, null));
        }
        if (all.isEmpty()) {
            return null;
        }
        return all.size() == 1 ? all.get(0) : new QueryFilter(QueryFilter.Op.AND, all, null, null);
    }

    private static QueryFilter condition(QueryFilter.Op op, String field, Object value) {
        return new QueryFilter(op, null, field, List.of(value));
    }

    private static List<QuerySort> order(String sort) {
        if (sort == null || sort.isBlank() || "name".equals(sort)) {
            return List.of(new QuerySort(Templates.NAME, false));
        }
        return switch (sort) {
            case "number" -> List.of(new QuerySort(Templates.NUMBER, false));
            case "newest" -> List.of(new QuerySort("createdAt", true));
            default -> throw ApiException.validation("sort", "Choose name, number or newest.");
        };
    }

    private static String text(DataRecord header, String field) {
        Object value = header.fields().get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
