package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The rules that route a document to an application by its number (MC1.1, ADR-0259), one row for each combination of
 * Identification and Part A (an empty Part A stands for every Part A). Every statement is a constant with parameters;
 * the organization is a parameter taken from the tenant context in addition to the forced row level security of the
 * table. Only this class names the table. The Identification and the Part A are the organization's own text: a rule
 * prints as a count and never in full.
 */
@Repository
class DocumentRoutingStore {

    /**
     * One rule.
     *
     * @param id the rule
     * @param identification the code of the Identification it applies to
     * @param partA the Part A it applies to, or an empty text for every Part A
     * @param application the key of the application the documents also belong to
     * @param version the version to send when changing or removing it
     */
    record Rule(UUID id, String identification, String partA, String application, long version) {

        /** Prints nothing typed. */
        @Override
        public String toString() {
            return "RoutingRule[redacted]";
        }
    }

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    DocumentRoutingStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Every live rule of the organization, in the order a person reads them. */
    List<Rule> rules() {
        return jdbc.sql("select id, category_code, part_a, application, version from inspection_document_routing "
                        + "where tenant_id = :tenant and deleted_at is null order by category_code, part_a")
                .param("tenant", tenant()).query(DocumentRoutingStore::rule).list();
    }

    /**
     * The application a combination routes to: the rule for the exact pair when there is one, else the rule for the
     * Identification alone. Empty when no rule fits.
     */
    Optional<String> applicationFor(String identification, String partA) {
        return jdbc.sql("select application from inspection_document_routing where tenant_id = :tenant "
                        + "and category_code = :category and part_a in (:part, '') and deleted_at is null "
                        + "order by part_a desc limit 1")
                .param("tenant", tenant()).param("category", identification).param("part", partA)
                .query(String.class).optional();
    }

    /** Makes a rule; false when the combination already has one. */
    boolean insert(String identification, String partA, String application, UUID actor) {
        return jdbc.sql("insert into inspection_document_routing (category_code, part_a, application, created_by, "
                        + "updated_by) values (:category, :part, :application, :actor, :actor) "
                        + "on conflict (tenant_id, category_code, part_a) where deleted_at is null do nothing")
                .param("category", identification).param("part", partA).param("application", application)
                .param("actor", actor).update() == 1;
    }

    /** Changes the application of a rule that is at the version read; false when it is not, or is gone. */
    boolean update(UUID id, String application, long version, UUID actor) {
        return jdbc.sql("update inspection_document_routing set application = :application, version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and version = :version "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("id", id).param("application", application)
                .param("version", version).param("actor", actor).update() == 1;
    }

    /** Removes a rule that is at the version read (marks it, as every removal is); false when it is not. */
    boolean remove(UUID id, long version, UUID actor) {
        return jdbc.sql("update inspection_document_routing set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and version = :version and deleted_at is null")
                .param("tenant", tenant()).param("id", id).param("version", version).param("actor", actor)
                .update() == 1;
    }

    private static Rule rule(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Rule(rs.getObject("id", UUID.class), rs.getString("category_code"), rs.getString("part_a"),
                rs.getString("application"), rs.getLong("version"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
