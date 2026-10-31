package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two small records behind the home page of the Quality Management System (FA9, ADR-0203, V082): the departments
 * of the person who started a fill-in, and what happened to a template. Every statement is a constant with parameters;
 * the organization is a parameter taken from the tenant context in addition to the forced row level security of the
 * tables. The database refuses a write that does not come from the act that owns it, and every change and delete
 * afterwards. Nothing here decides who may see a record: the module judges identifiers with the record store.
 */
@Repository
class QualityHomeStore {

    /** A location and a department, as the codes of the organization's lists. */
    record Place(String location, String department) {

        /** Prints nothing: the codes are the organization's text. */
        @Override
        public String toString() {
            return "Place[redacted]";
        }
    }

    /**
     * What happened to a template.
     *
     * @param templateId the template
     * @param kind {@code PUBLISHED}, {@code REVISED}, {@code UNPUBLISHED} or {@code RETIRED}
     * @param versionNo the version it was about
     * @param revisionNo the revision number a published version got, or null
     * @param at when
     * @param by who did it
     */
    record Event(UUID templateId, String kind, int versionNo, Integer revisionNo, Instant at, UUID by) {
    }

    private static final String LIVE_PLACES = "select template_id, location, department from inspection_publication "
            + "where tenant_id = :tenant and template_id in (:templates) and revoked_at is null "
            + "and deleted_at is null";
    private static final String FILL_PLACES = "select fill_id, location, department from inspection_fill_department "
            + "where tenant_id = :tenant and fill_id in (:fills) and deleted_at is null";
    private static final String EVENTS = "select template_id, kind, version_no, revision_no, created_at, created_by "
            + "from inspection_template_event where tenant_id = :tenant and template_object = :object "
            + "and created_at >= :since and deleted_at is null order by created_at desc, id desc limit :limit";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    QualityHomeStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Writes the departments of a fill-in, each in a statement that also raises the setting the guard asks for, so
     * nothing else in the transaction can write under it.
     */
    void recordFillPlaces(UUID fillId, Collection<Place> places, UUID actor) {
        for (Place place : places) {
            jdbc.sql("with allowed as (select set_config('platform.fill_department_write', 'on', true)) "
                            + "insert into inspection_fill_department (tenant_id, fill_id, location, department, "
                            + "created_by, updated_by) select :tenant, :fill, :location, :department, :actor, "
                            + ":actor from allowed on conflict do nothing")
                    .param("tenant", tenant()).param("fill", fillId).param("location", place.location())
                    .param("department", place.department()).param("actor", actor).update();
        }
    }

    /** Writes what happened to a template, in the transaction of the act that did it. */
    void recordEvent(String templateObject, UUID templateId, String kind, int versionNo, Integer revisionNo,
            UUID actor) {
        jdbc.sql("with allowed as (select set_config('platform.template_event_write', 'on', true)) "
                        + "insert into inspection_template_event (tenant_id, template_id, kind, version_no, "
                        + "revision_no, created_by, updated_by, template_object) select :tenant, :template, :kind, "
                        + ":version, :revision, :actor, :actor, :object from allowed")
                .param("tenant", tenant()).param("template", templateId).param("kind", kind)
                .param("object", templateObject)
                .param("version", versionNo).param("revision", revisionNo, java.sql.Types.INTEGER)
                .param("actor", actor).update();
    }

    /** The places the groups a template is published to now stand for, by template; one without is not in the map. */
    Map<UUID, Set<Place>> livePlaces(Collection<UUID> templateIds) {
        return places(templateIds, LIVE_PLACES, "templates", "template_id");
    }

    /** The departments recorded for these fill-ins, by fill-in; one without is not in the map. */
    Map<UUID, Set<Place>> fillPlaces(Collection<UUID> fillIds) {
        return places(fillIds, FILL_PLACES, "fills", "fill_id");
    }

    /**
     * What happened to the templates of one set since a moment, newest first, at most the limit. Another set's events
     * are never read, so they can neither show nor fill the bound (FA13B, ADR-0226).
     */
    List<Event> eventsSince(String templateObject, Instant since, int limit) {
        return jdbc.sql(EVENTS).param("tenant", tenant()).param("object", templateObject)
                .param("since", java.sql.Timestamp.from(since)).param("limit", limit)
                .query(QualityHomeStore::event).list();
    }

    private Map<UUID, Set<Place>> places(Collection<UUID> ids, String sql, String param, String column) {
        Map<UUID, Set<Place>> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.sql(sql).param("tenant", tenant()).param(param, List.copyOf(ids)).query((ResultSet rs) -> {
            found.computeIfAbsent(rs.getObject(column, UUID.class), key -> new HashSet<>())
                    .add(new Place(rs.getString("location"), rs.getString("department")));
        });
        return found;
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Event event(ResultSet rs, int row) throws SQLException {
        OffsetDateTime at = rs.getObject("created_at", OffsetDateTime.class);
        return new Event(rs.getObject("template_id", UUID.class), rs.getString("kind"), rs.getInt("version_no"),
                rs.getObject("revision_no", Integer.class), at.toInstant(), rs.getObject("created_by", UUID.class));
    }
}
