package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two sheet options of a template (FA7, ADR-0201): one row for each template that ever had them set. Every
 * statement is a constant with parameters; the organization is a parameter taken from the tenant context in addition to
 * the forced row level security of the table. The database refuses a write that does not raise the transaction setting,
 * which only {@link #set} does, so the options change only through the acts of {@link TemplatePublishing}; who may
 * ask for that is decided there, not here.
 */
@Repository
class TemplateOptionStore {

    /**
     * The options of one template.
     *
     * @param viewOnly nobody can start a fill-in from it
     * @param allowDownload the sheet may be given as a file to everybody who can read it
     */
    record Options(boolean viewOnly, boolean allowDownload) {

        /** What a template has before anything is set: both off. */
        static final Options NONE = new Options(false, false);
    }

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    TemplateOptionStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** The options of the template; both off when none were ever set. */
    Options find(UUID templateId) {
        return jdbc.sql("select view_only, allow_download from inspection_template_option where tenant_id = :tenant "
                        + "and template_id = :template and deleted_at is null")
                .param("tenant", tenant()).param("template", templateId)
                .query((rs, row) -> new Options(rs.getBoolean("view_only"), rs.getBoolean("allow_download")))
                .optional().orElse(Options.NONE);
    }

    /**
     * Sets both options. One statement that also raises the setting the guard asks for, so nothing else in the
     * transaction can write under it; a template with a row has the row changed and its version raised.
     */
    void set(UUID templateId, Options options, UUID actor) {
        jdbc.sql("with allowed as (select set_config('platform.template_option_write', 'on', true)) "
                        + "insert into inspection_template_option (tenant_id, template_id, view_only, allow_download, "
                        + "created_by, updated_by) select :tenant, :template, :viewOnly, :allowDownload, :actor, "
                        + ":actor from allowed on conflict (tenant_id, template_id) where deleted_at is null "
                        + "do update set view_only = excluded.view_only, allow_download = excluded.allow_download, "
                        + "version = inspection_template_option.version + 1, updated_by = :actor")
                .param("tenant", tenant()).param("template", templateId).param("viewOnly", options.viewOnly())
                .param("allowDownload", options.allowDownload()).param("actor", actor).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
