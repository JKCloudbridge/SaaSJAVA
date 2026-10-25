package app.platform.inspection.internal;

import app.platform.inspection.internal.AudienceChoices.Place;
import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a step of an approval suggested for a template (FA6G, ADR-0200): one row for each place. Every statement is a
 * constant with parameters; the organization is a parameter taken from the tenant context in addition to the forced
 * row level security of the table. The database refuses a write that does not come from the step's approval and any
 * change or delete afterwards (V076); only {@link AudienceSuggestions} writes, from inside the approval's own
 * transaction.
 */
@Repository
class AudienceSuggestionStore {

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    AudienceSuggestionStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Whether the request already holds a suggestion. */
    boolean exists(UUID requestId) {
        return jdbc.sql("select exists (select 1 from inspection_audience_suggestion where tenant_id = :tenant "
                        + "and request_id = :request and deleted_at is null)")
                .param("tenant", tenant()).param("request", requestId).query(Boolean.class).single();
    }

    /**
     * Writes the places, each in a statement that also raises the setting the guard asks for, so nothing else in the
     * transaction can write under it.
     */
    void insert(UUID requestId, UUID templateId, int stepPosition, List<Place> places, UUID actor) {
        for (Place place : places) {
            jdbc.sql("with allowed as (select set_config('platform.audience_suggestion_write', 'on', true)) "
                            + "insert into inspection_audience_suggestion (tenant_id, request_id, template_id, "
                            + "step_position, location, department, created_by, updated_by) "
                            + "select :tenant, :request, :template, :step, :location, :department, :actor, :actor "
                            + "from allowed")
                    .param("tenant", tenant()).param("request", requestId).param("template", templateId)
                    .param("step", stepPosition).param("location", place.location())
                    .param("department", place.department(), java.sql.Types.VARCHAR).param("actor", actor).update();
        }
    }

    /** The places the request holds, in the order they were written. */
    List<Place> ofRequest(UUID requestId) {
        return jdbc.sql("select location, department from inspection_audience_suggestion where tenant_id = :tenant "
                        + "and request_id = :request and deleted_at is null order by created_at, id")
                .param("tenant", tenant()).param("request", requestId)
                .query((rs, row) -> new Place(rs.getString("location"), rs.getString("department"))).list();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
