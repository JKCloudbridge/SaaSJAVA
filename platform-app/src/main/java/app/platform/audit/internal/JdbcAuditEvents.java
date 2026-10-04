package app.platform.audit.internal;

import app.platform.audit.AuditEvents;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiPageResponse;
import app.platformapi.AuditEventView;
import app.platformapi.Cursors;
import app.platformapi.PageRequest;
import app.platformapi.Pagination;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two readers of the audit table (ADR-0056). Each query names the audience itself and the database repeats it (row
 * level security, migration V029): a mistake here shows nothing extra, because the policy would hide it. The
 * transaction opens after the tenant context, so the policy sees the organization.
 *
 * <p>Facts that are free text typed by a platform person ({@code reason_text}) are never returned, and the internal
 * reason of a record is returned only for the kinds where it may be shown ({@link AuditAudience#reasonVisible}).
 */
@Service
class JdbcAuditEvents implements AuditEvents {

    private static final Set<String> HIDDEN_FACTS = Set.of("reason_text");
    private static final TypeReference<Map<String, String>> FACTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcAuditEvents(JdbcClient jdbc, TenantContexts contexts, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.transaction = transaction;
    }

    @Override
    public ApiPageResponse<AuditEventView> ofOrganization(Query query, PageRequest page) {
        UUID tenant = contexts.require().tenantId().value();
        return transaction.execute(status -> read("context_tenant_id = :tenant and audience_organization",
                Map.of("tenant", tenant), query, page));
    }

    @Override
    public ApiPageResponse<AuditEventView> ofPlatform(Query query, PageRequest page) {
        if (contexts.current().isPresent()) {
            throw new IllegalStateException("The platform's audit events are read without a tenant context");
        }
        return transaction.execute(status -> read("audience_platform", Map.of(), query, page));
    }

    private ApiPageResponse<AuditEventView> read(String audience, Map<String, Object> audienceParams, Query query,
            PageRequest page) {
        StringBuilder sql = new StringBuilder("select id, occurred_at, event_type, outcome, actor_user_id, reason, "
                + "source, object_key, record_id, old_value, new_value, cast(attributes as text) as facts "
                + "from audit_record where deleted_at is null and ").append(audience);
        Map<String, Object> params = new LinkedHashMap<>(audienceParams);
        if (query.from() != null) {
            sql.append(" and occurred_at >= :from");
            params.put("from", Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" and occurred_at < :to");
            params.put("to", Timestamp.from(query.to()));
        }
        if (query.actor() != null) {
            sql.append(" and actor_user_id = :actor");
            params.put("actor", query.actor());
        }
        if (query.kind() != null) {
            sql.append(" and (event_type = :kind or event_type like :family)");
            params.put("kind", query.kind());
            params.put("family", query.kind() + ".%");
        }
        if (query.target() != null) {
            sql.append(" and (record_id = :target or exists (select 1 from jsonb_each_text(attributes) fact "
                    + "where fact.value = :target))");
            params.put("target", query.target());
        }
        Position after = Cursors.decode(page.cursor()).flatMap(Position::parse).orElse(null);
        if (after != null) {
            sql.append(" and (occurred_at, id) < (:afterTime, :afterId)");
            params.put("afterTime", Timestamp.from(after.time()));
            params.put("afterId", after.id());
        }
        sql.append(" order by occurred_at desc, id desc limit :limit");
        params.put("limit", page.limit() + 1);

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> param : params.entrySet()) {
            statement = statement.param(param.getKey(), param.getValue());
        }
        List<Row> rows = statement.query((rs, n) -> row(rs)).list();
        boolean more = rows.size() > page.limit();
        List<Row> shown = more ? rows.subList(0, page.limit()) : rows;
        List<AuditEventView> views = new ArrayList<>();
        for (Row row : shown) {
            views.add(row.view());
        }
        Pagination pagination = more
                ? Pagination.more(page.limit(), Cursors.encode(new Position(shown.get(shown.size() - 1).time(),
                        shown.get(shown.size() - 1).view().id()).text()))
                : Pagination.last(page.limit());
        return ApiPageResponse.of(views, pagination);
    }

    private Row row(ResultSet rs) throws SQLException {
        String type = rs.getString("event_type");
        Map<String, String> facts = new LinkedHashMap<>(json.readValue(rs.getString("facts"), FACTS));
        facts.keySet().removeAll(HIDDEN_FACTS);
        Instant time = rs.getTimestamp("occurred_at").toInstant();
        String reason = AuditAudience.reasonVisible(type) ? rs.getString("reason") : null;
        return new Row(time, new AuditEventView(rs.getObject("id", UUID.class), time, type, rs.getString("outcome"),
                rs.getObject("actor_user_id", UUID.class), rs.getString("source"), rs.getString("object_key"),
                rs.getString("record_id"), rs.getString("old_value"), rs.getString("new_value"), reason, facts));
    }

    /** A row with the exact time for the cursor (the view carries the same instant). */
    private record Row(Instant time, AuditEventView view) {
    }

    /** Where a page ended: the time and the identifier of its last event. */
    private record Position(Instant time, UUID id) {

        String text() {
            return time.getEpochSecond() + ":" + time.getNano() + ":" + id;
        }

        static java.util.Optional<Position> parse(String text) {
            String[] parts = text.split(":", 3);
            if (parts.length != 3) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.of(new Position(
                        Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                        UUID.fromString(parts[2])));
            } catch (RuntimeException e) {
                return java.util.Optional.empty();
            }
        }
    }
}
