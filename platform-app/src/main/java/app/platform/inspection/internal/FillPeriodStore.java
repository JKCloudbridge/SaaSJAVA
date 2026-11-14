package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The claim of one fill-in for a document, a plant and a month (MC2A, ADR-0260): the rows of
 * {@code inspection_fill_period}, which no other class names (a test scans the sources). The application looks first
 * and says in words that the month is taken; the unique index of the table is the backstop for two requests that
 * look at the same moment, and its violation is turned into the same words by {@link FillIns}. Nothing a row holds
 * is written to a log: the plant and the month are the organization's text.
 */
@Repository
class FillPeriodStore {

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    FillPeriodStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Whether a fill-in already exists for the document, the plant and the month. */
    boolean taken(UUID templateId, String plant, String period) {
        return jdbc.sql("select exists (select 1 from inspection_fill_period where tenant_id = :tenant "
                        + "and template_id = :template and plant = :plant and period = :period "
                        + "and deleted_at is null)")
                .param("tenant", tenant()).param("template", templateId).param("plant", plant)
                .param("period", period).query(Boolean.class).single();
    }

    /**
     * Writes the claim in the transaction that makes the fill-in.
     *
     * @throws org.springframework.dao.DuplicateKeyException when another fill-in holds the month (the caller turns it
     *         into its own words and the whole transaction is undone)
     */
    void claim(UUID templateId, UUID fillId, String plant, String period, UUID actor) {
        jdbc.sql("insert into inspection_fill_period (tenant_id, template_id, fill_id, plant, period, created_by, "
                        + "updated_by) values (:tenant, :template, :fill, :plant, :period, :actor, :actor)")
                .param("tenant", tenant()).param("template", templateId).param("fill", fillId)
                .param("plant", plant).param("period", period).param("actor", actor).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
