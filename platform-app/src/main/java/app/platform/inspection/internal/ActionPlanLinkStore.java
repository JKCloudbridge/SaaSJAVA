package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which document is the Action plan form of a document (MC2B, ADR-0261): one row for each document that names
 * one. Every statement is a constant with parameters; the organization is a parameter taken from the tenant context in
 * addition to the forced row level security of the table. Only this class names the table. The documents are the
 * organization's: nothing here is printed.
 */
@Repository
class ActionPlanLinkStore {

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    ActionPlanLinkStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** The document that is the Action plan form of this one, when it names one. */
    Optional<UUID> actionFormOf(UUID templateId) {
        return jdbc.sql("select action_template_id from inspection_action_plan_link where tenant_id = :tenant "
                        + "and template_id = :template and deleted_at is null")
                .param("tenant", tenant()).param("template", templateId).query(UUID.class).optional();
    }

    /** Whether any document names this one as its Action plan form. */
    boolean isActionForm(UUID templateId) {
        return jdbc.sql("select count(*) from inspection_action_plan_link where tenant_id = :tenant "
                        + "and action_template_id = :template and deleted_at is null")
                .param("tenant", tenant()).param("template", templateId).query(Long.class).single() > 0;
    }

    /** Names the Action plan form of a document, replacing the one it had. */
    void set(UUID templateId, UUID actionTemplateId, UUID actor) {
        clear(templateId, actor);
        jdbc.sql("insert into inspection_action_plan_link (template_id, action_template_id, created_by, updated_by) "
                        + "values (:template, :action, :actor, :actor)")
                .param("template", templateId).param("action", actionTemplateId).param("actor", actor).update();
    }

    /** Takes the Action plan form back from a document (marks the row, as every removal is). */
    void clear(UUID templateId, UUID actor) {
        jdbc.sql("update inspection_action_plan_link set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant "
                        + "and template_id = :template and deleted_at is null")
                .param("tenant", tenant()).param("template", templateId).param("actor", actor).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
