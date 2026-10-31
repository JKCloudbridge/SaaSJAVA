package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The change requests of templates and the comments on them (FA5, ADR-0191). Every statement is a constant with
 * parameters; the organization is a parameter taken from the tenant context in addition to the forced row level
 * security of the tables. Who may see or do what is decided by {@link ChangeRequests}, not here; the database refuses a
 * delete, a changed comment, a move of a request that the lifecycle lacks and a comment on a closed request whatever is
 * asked (V067).
 *
 * <p>The text of a request is a person's own: no row ever prints it.
 */
@Repository
class ChangeRequestStore {

    /**
     * One request.
     *
     * @param id its identifier
     * @param templateId the template
     * @param versionNo the version the person read
     * @param title what the request is called (text of the organization)
     * @param status the status name
     * @param decisionNote the reason for declining, or null (text of the organization)
     * @param decidedAt when it was last answered, or null while it is open
     * @param decidedBy who answered, or null
     * @param resultingVersion the version it was turned into, or null
     * @param version the row version
     * @param createdAt when it was made
     * @param createdBy who asked
     * @param requesterDepartment the department of the person when they asked, in the words of the lists then, or null
     * @param headerId the record the approval engine reviews for this request (FA6E, ADR-0199), or null when the
     *        request is answered directly
     * @param templateObject the API name of the template object of the set the request belongs to (FA13B, ADR-0226)
     */
    record RequestRow(UUID id, UUID templateId, int versionNo, String title, String status, String decisionNote,
            Instant decidedAt, UUID decidedBy, Integer resultingVersion, long version, Instant createdAt,
            UUID createdBy, String requesterDepartment, UUID headerId, String templateObject) {

        ChangeRequestStatus state() {
            return ChangeRequestStatus.of(status).orElseThrow();
        }

        /** Whether the request is decided by an approval (it has its review record) and not by one answer. */
        boolean reviewed() {
            return headerId != null;
        }

        /** Prints the identity only: the title and the reason are the organization's text. */
        @Override
        public String toString() {
            return "RequestRow[" + id + "]";
        }
    }

    /**
     * One comment.
     *
     * @param id its identifier
     * @param requestId the request
     * @param rowId the row it points at, or null
     * @param columnId the column it points at, or null
     * @param label the words that named the place when the comment was made (text of the organization)
     * @param body what was said (text of the organization)
     * @param createdAt when it was written
     * @param createdBy who wrote it
     */
    record CommentRow(UUID id, UUID requestId, String rowId, String columnId, String label, String body,
            Instant createdAt, UUID createdBy) {

        ChangeAnchor anchor() {
            return new ChangeAnchor(rowId, columnId);
        }

        /** Prints the identity only. */
        @Override
        public String toString() {
            return "CommentRow[" + id + "]";
        }
    }

    private static final String REQUEST_COLUMNS = "id, template_id, version_no, title, status, decision_note, "
            + "decided_at, decided_by, resulting_version, version, created_at, created_by, requester_department, "
            + "header_id, template_object";

    private static final String COMMENT_COLUMNS = "id, request_id, row_id, column_id, anchor_label, body, created_at, "
            + "created_by";

    private static final String OF_TEMPLATE = "tenant_id = :tenant and template_id = :template and deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    ChangeRequestStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Takes the lock that serializes everything that adds to or answers the requests of one template, so that the
     * limits hold and two instances turning one request into a version are decided one after the other. Held until the
     * transaction ends.
     */
    void lock(UUID templateId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "change-requests:" + tenant() + ":" + templateId).query().listOfRows();
    }

    RequestRow insertRequest(String templateObject, UUID templateId, int versionNo, String title, UUID actor,
            String department, UUID headerId) {
        return jdbc.sql("insert into inspection_change_request (tenant_id, template_id, version_no, title, "
                        + "created_by, updated_by, requester_department, header_id, template_object) values (:tenant, "
                        + ":template, :version, :title, :actor, :actor, cast(:department as text), "
                        + "cast(:header as uuid), :object) returning " + REQUEST_COLUMNS)
                .param("tenant", tenant()).param("template", templateId).param("version", versionNo)
                .param("title", title).param("actor", actor).param("department", department)
                .param("header", headerId).param("object", templateObject).query(ChangeRequestStore::request)
                .single();
    }

    /** One request by its identifier, whatever template it is about. */
    Optional<RequestRow> findById(UUID requestId) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from inspection_change_request where tenant_id = :tenant "
                        + "and id = :id and deleted_at is null")
                .param("tenant", tenant()).param("id", requestId).query(ChangeRequestStore::request).optional();
    }

    /** The request a review record belongs to. */
    Optional<RequestRow> findByHeader(UUID headerId) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from inspection_change_request where tenant_id = :tenant "
                        + "and header_id = :header and deleted_at is null")
                .param("tenant", tenant()).param("header", headerId).query(ChangeRequestStore::request).optional();
    }

    /** How the cross-template list is ordered. The column is chosen here and never taken from a request. */
    enum Order {
        DATE("created_at", "timestamptz"), VERSION("version_no", "integer"), STATUS("status", "text");

        private final String column;
        private final String type;

        Order(String column, String type) {
            this.column = column;
            this.type = type;
        }
    }

    /**
     * What the cross-template list asks of the store.
     *
     * @param requester only this person's requests, or null for every request
     * @param titleLike a text to find in the title, already made a like pattern with {@code !} as the escape, or null
     * @param requesters people whose requests also match the search (the requester's name matched), or empty
     * @param status only this status, or null
     * @param template only this template, or null
     * @param order the column to order by
     * @param descending whether the order is descending
     * @param afterValue the value of the ordered column in the last row seen, as text, or null for the first batch
     * @param afterId the identifier of the last row seen, or null
     * @param templateObject the template object of the one set the list is about (FA13B, ADR-0226): the list never
     *        reads another set's requests
     */
    record ListFilter(UUID requester, String titleLike, Collection<UUID> requesters, String status, UUID template,
            Order order, boolean descending, String afterValue, UUID afterId, String templateObject) {
    }

    /** The value of a row in the column the list is ordered by, as the text the cursor holds. */
    static String orderValue(RequestRow row, Order order) {
        return switch (order) {
            case DATE -> row.createdAt().toString();
            case VERSION -> Integer.toString(row.versionNo());
            case STATUS -> row.status();
            default -> throw new IllegalStateException("Unreachable: every order is handled above");
        };
    }

    /**
     * A batch of requests of every template, in the order asked, after the key given. Who may see each is decided by
     * the caller; this only narrows by what the screen asked for. The search matches the title, or a person among
     * {@code requesters}.
     */
    List<RequestRow> page(ListFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("select " + REQUEST_COLUMNS
                + " from inspection_change_request where tenant_id = :tenant and template_object = :object "
                + "and deleted_at is null");
        String direction = filter.descending() ? "desc" : "asc";
        String compare = filter.descending() ? "<" : ">";
        sql.append(" and (cast(:requester as uuid) is null or created_by = cast(:requester as uuid))");
        sql.append(" and (cast(:status as text) is null or status = cast(:status as text))");
        sql.append(" and (cast(:template as uuid) is null or template_id = cast(:template as uuid))");
        boolean search = filter.titleLike() != null;
        if (search) {
            sql.append(" and (title ilike :like escape '!'");
            if (!filter.requesters().isEmpty()) {
                sql.append(" or created_by in (:people)");
            }
            sql.append(')');
        }
        boolean after = filter.afterValue() != null && filter.afterId() != null;
        if (after) {
            sql.append(" and (").append(filter.order().column).append(", id) ").append(compare)
                    .append(" (cast(:afterValue as ").append(filter.order().type).append("), :afterId)");
        }
        sql.append(" order by ").append(filter.order().column).append(' ').append(direction).append(", id ")
                .append(direction).append(" limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", tenant())
                .param("object", filter.templateObject()).param("requester", filter.requester())
                .param("status", filter.status())
                .param("template", filter.template()).param("limit", limit);
        if (search) {
            spec = spec.param("like", filter.titleLike());
            if (!filter.requesters().isEmpty()) {
                spec = spec.param("people", List.copyOf(filter.requesters()));
            }
        }
        if (after) {
            spec = spec.param("afterValue", filter.afterValue()).param("afterId", filter.afterId());
        }
        return spec.query(ChangeRequestStore::request).list();
    }

    /**
     * The people who made requests of one set (every request, or the caller's own), for matching a name in a search.
     */
    List<UUID> requesters(String templateObject, UUID only, int most) {
        return jdbc.sql("select distinct created_by from inspection_change_request where tenant_id = :tenant "
                        + "and template_object = :object and deleted_at is null "
                        + "and (cast(:only as uuid) is null or created_by = cast(:only as uuid)) limit :most")
                .param("tenant", tenant()).param("object", templateObject).param("only", only)
                .param("most", most).query(UUID.class).list();
    }

    /**
     * How many requests of each of these templates still wait for someone (open or accepted); of one person only when
     * {@code requester} is given. A template without any is not in the map.
     */
    Map<UUID, Integer> toActCounts(String templateObject, Collection<UUID> templateIds, UUID requester) {
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql("select template_id, count(*) as total from inspection_change_request where tenant_id = :tenant "
                        + "and template_object = :object "
                        + "and template_id in (:templates) and status in ('OPEN', 'ACCEPTED') and deleted_at is null "
                        + "and (cast(:requester as uuid) is null or created_by = cast(:requester as uuid)) "
                        + "group by template_id")
                .param("tenant", tenant()).param("object", templateObject)
                .param("templates", List.copyOf(templateIds)).param("requester", requester)
                .query((rs, row) -> {
                    counts.put(rs.getObject("template_id", UUID.class), rs.getInt("total"));
                    return 0;
                }).list();
        return counts;
    }

    CommentRow insertComment(UUID requestId, UUID templateId, ChangeAnchor anchor, String label, String body,
            UUID actor) {
        return jdbc.sql("insert into inspection_change_comment (tenant_id, request_id, template_id, row_id, "
                        + "column_id, anchor_label, body, created_by, updated_by) values (:tenant, :request, "
                        + ":template, cast(:row as text), cast(:column as text), cast(:label as text), :body, "
                        + ":actor, :actor) returning " + COMMENT_COLUMNS)
                .param("tenant", tenant()).param("request", requestId).param("template", templateId)
                .param("row", anchor.rowId()).param("column", anchor.columnId()).param("label", label)
                .param("body", body).param("actor", actor).query(ChangeRequestStore::comment).single();
    }

    Optional<RequestRow> find(UUID templateId, UUID requestId) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from inspection_change_request where " + OF_TEMPLATE
                        + " and id = :id")
                .param("tenant", tenant()).param("template", templateId).param("id", requestId)
                .query(ChangeRequestStore::request).optional();
    }

    /** The requests of a template, newest first; of one person only when {@code requester} is given. */
    List<RequestRow> requests(UUID templateId, UUID requester) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from inspection_change_request where " + OF_TEMPLATE
                        + " and (cast(:requester as uuid) is null or created_by = cast(:requester as uuid)) "
                        + "order by created_at desc, id desc")
                .param("tenant", tenant()).param("template", templateId).param("requester", requester)
                .query(ChangeRequestStore::request).list();
    }

    /** The comments of these requests, oldest first. */
    List<CommentRow> comments(Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COMMENT_COLUMNS + " from inspection_change_comment where tenant_id = :tenant "
                        + "and request_id in (:requests) and deleted_at is null order by created_at, id")
                .param("tenant", tenant()).param("requests", List.copyOf(requestIds))
                .query(ChangeRequestStore::comment).list();
    }

    int countOnTemplate(UUID templateId) {
        return count("select count(*) from inspection_change_request where " + OF_TEMPLATE, templateId);
    }

    /** How many requests are in progress on the template: open (or left accepted by the earlier way of working). */
    int countInProgress(UUID templateId) {
        return count("select count(*) from inspection_change_request where " + OF_TEMPLATE
                + " and status in ('OPEN', 'ACCEPTED')", templateId);
    }

    int countOpenBy(UUID templateId, UUID person) {
        return jdbc.sql("select count(*) from inspection_change_request where " + OF_TEMPLATE
                        + " and status = 'OPEN' and created_by = :person")
                .param("tenant", tenant()).param("template", templateId).param("person", person)
                .query(Integer.class).single();
    }

    int countComments(UUID requestId) {
        return jdbc.sql("select count(*) from inspection_change_comment where tenant_id = :tenant "
                        + "and request_id = :request and deleted_at is null")
                .param("tenant", tenant()).param("request", requestId).query(Integer.class).single();
    }

    /**
     * Answers a request: it moves from the status it was in to another. False when it is no longer in that status
     * (somebody was first), which is how two answers arriving together end with one.
     */
    boolean answer(UUID requestId, ChangeRequestStatus from, ChangeRequestStatus to, String note,
            Integer resultingVersion, UUID actor) {
        return jdbc.sql("update inspection_change_request set status = :to, decision_note = cast(:note as text), "
                        + "decided_at = now(), decided_by = :actor, resulting_version = cast(:result as integer), "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and status = :from and deleted_at is null")
                .param("to", to.name()).param("note", note).param("actor", actor).param("result", resultingVersion)
                .param("tenant", tenant()).param("id", requestId).param("from", from.name()).update() == 1;
    }

    private int count(String sql, UUID templateId) {
        return jdbc.sql(sql).param("tenant", tenant()).param("template", templateId).query(Integer.class).single();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static RequestRow request(ResultSet rs, int row) throws SQLException {
        return new RequestRow(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("version_no"), rs.getString("title"), rs.getString("status"),
                rs.getString("decision_note"), instant(rs, "decided_at"), rs.getObject("decided_by", UUID.class),
                rs.getObject("resulting_version", Integer.class), rs.getLong("version"), instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class), rs.getString("requester_department"),
                rs.getObject("header_id", UUID.class), rs.getString("template_object"));
    }

    private static CommentRow comment(ResultSet rs, int row) throws SQLException {
        return new CommentRow(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                rs.getString("row_id"), rs.getString("column_id"), rs.getString("anchor_label"), rs.getString("body"),
                instant(rs, "created_at"), rs.getObject("created_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
