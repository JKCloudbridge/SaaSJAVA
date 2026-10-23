package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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
     */
    record RequestRow(UUID id, UUID templateId, int versionNo, String title, String status, String decisionNote,
            Instant decidedAt, UUID decidedBy, Integer resultingVersion, long version, Instant createdAt,
            UUID createdBy) {

        ChangeRequestStatus state() {
            return ChangeRequestStatus.of(status).orElseThrow();
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
            + "decided_at, decided_by, resulting_version, version, created_at, created_by";

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

    RequestRow insertRequest(UUID templateId, int versionNo, String title, UUID actor) {
        return jdbc.sql("insert into inspection_change_request (tenant_id, template_id, version_no, title, "
                        + "created_by, updated_by) values (:tenant, :template, :version, :title, :actor, :actor) "
                        + "returning " + REQUEST_COLUMNS)
                .param("tenant", tenant()).param("template", templateId).param("version", versionNo)
                .param("title", title).param("actor", actor).query(ChangeRequestStore::request).single();
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

    int countOpen(UUID templateId) {
        return count("select count(*) from inspection_change_request where " + OF_TEMPLATE + " and status = 'OPEN'",
                templateId);
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

    /** A request that was done goes back to open (its version was not used); the answer is cleared. */
    boolean reopen(UUID requestId, UUID actor) {
        return jdbc.sql("update inspection_change_request set status = 'OPEN', decision_note = null, "
                        + "decided_at = null, decided_by = null, resulting_version = null, version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and status = 'DONE' "
                        + "and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", requestId).update() == 1;
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
                rs.getObject("created_by", UUID.class));
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
