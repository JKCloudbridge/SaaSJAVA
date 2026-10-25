package app.platform.inspection.internal;

import app.platform.inspection.internal.RegisterRules.Completed;
import app.platform.inspection.internal.RegisterRules.Raised;
import app.platform.tenant.TenantContexts;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The register forms of change requests (FA8, ADR-0202). Every statement is a constant with parameters; the
 * organization is a parameter taken from the tenant context besides the forced row level security of the table. Who may
 * see or complete a register is decided by {@link ChangeRequestRegisters}, not here; the database refuses a change of
 * what was written when the request was raised, a completion after the request was answered, a second stamp of the new
 * revision, a delete and a write outside this class ({@code ChangeRegisterGuardIT}).
 *
 * <p>The window the database asks for is a transaction setting that is switched on for the one statement and off again
 * after it, so nothing else in the transaction can write under it. The words in a row are the organization's: no row
 * ever prints them.
 */
@Repository
class RegisterStore {

    /**
     * One register.
     *
     * @param id its identifier
     * @param requestId the change request it belongs to
     * @param templateId the template the request is about
     * @param values what the form says
     * @param newRevisionAt when the new revision was published, or null
     * @param version the row version, to send back when completing it
     */
    record RegisterRow(UUID id, UUID requestId, UUID templateId, RegisterValues values, Instant newRevisionAt,
            long version) {

        /** Prints the identity only. */
        @Override
        public String toString() {
            return "RegisterRow[" + id + "]";
        }
    }

    /**
     * The facts the platform writes when a request is raised.
     *
     * @param documentName the template's name
     * @param documentNo the template's number, or null
     * @param revNo the revision in use, two digits
     * @param requestedBy the name of the person who asks, or null
     * @param department the department of the person, or null
     */
    record Facts(String documentName, String documentNo, String revNo, String requestedBy, String department) {

        /** Prints a marker only. */
        @Override
        public String toString() {
            return "Facts[redacted]";
        }
    }

    /**
     * What the publish writes into the register.
     *
     * @param newRevNo the revision published, two digits
     * @param effectiveOn the effective day to keep when the approvers left it blank
     * @param approvedBy the approvals to keep when the approvers left them blank
     * @param signatureNote the signature words to keep when the approvers left them blank
     */
    record Stamp(String newRevNo, LocalDate effectiveOn, String approvedBy, String signatureNote) {

        /** Prints a marker only. */
        @Override
        public String toString() {
            return "Stamp[redacted]";
        }
    }

    private static final String COLUMNS = "id, request_id, template_id, request_no, change_requested, "
            + "basis_for_change, horizontal_deployment, document_name, document_no, rev_no, requested_by_name, "
            + "requested_at, department, obsoleted_format, obsoleted_effective_on, approved_by, signature_note, "
            + "effective_on, server_updated, master_list_updated, new_rev_no, new_revision_at, version";

    private static final String WRITE = "platform.change_register_write";
    private static final String RESULT = "platform.change_register_result";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    RegisterStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Writes the register of a request that was just raised. */
    RegisterRow insert(UUID requestId, UUID templateId, Raised raised, Facts facts, UUID actor) {
        window(WRITE, "on");
        RegisterRow made = jdbc.sql("insert into inspection_change_register (tenant_id, request_id, template_id, "
                        + "request_no, change_requested, basis_for_change, horizontal_deployment, document_name, "
                        + "document_no, rev_no, requested_by_name, department, obsoleted_format, "
                        + "obsoleted_effective_on, created_by, updated_by) values (:tenant, :request, :template, "
                        + ":requestNo, :change, cast(:basis as text), cast(:horizontal as text), :documentName, "
                        + "cast(:documentNo as text), :revNo, cast(:by as text), cast(:department as text), "
                        + "cast(:obsoleted as text), cast(:obsoletedOn as date), :actor, :actor) returning " + COLUMNS)
                .param("tenant", tenant()).param("request", requestId).param("template", templateId)
                .param("requestNo", raised.requestNo()).param("change", raised.changeRequested())
                .param("basis", raised.basisForChange()).param("horizontal", raised.horizontalDeployment())
                .param("documentName", facts.documentName()).param("documentNo", facts.documentNo())
                .param("revNo", facts.revNo()).param("by", facts.requestedBy())
                .param("department", facts.department()).param("obsoleted", raised.obsoletedFormat())
                .param("obsoletedOn", raised.obsoletedEffectiveOn() == null ? null
                        : Date.valueOf(raised.obsoletedEffectiveOn()))
                .param("actor", actor).query(RegisterStore::row).single();
        window(WRITE, "off");
        return made;
    }

    /** The register of a request. */
    Optional<RegisterRow> find(UUID requestId) {
        return jdbc.sql("select " + COLUMNS + " from inspection_change_register where tenant_id = :tenant "
                        + "and request_id = :request and deleted_at is null")
                .param("tenant", tenant()).param("request", requestId).query(RegisterStore::row).optional();
    }

    /**
     * Completes the bottom of the register. The version in the where clause is the optimistic check: nothing changes
     * when it is stale.
     *
     * @return the register after the change, or empty when it was changed since it was read
     */
    Optional<RegisterRow> complete(UUID requestId, long version, Completed completed, UUID actor) {
        window(WRITE, "on");
        Optional<RegisterRow> saved = jdbc.sql("update inspection_change_register set department = "
                        + "cast(:department as text), obsoleted_format = cast(:obsoleted as text), "
                        + "obsoleted_effective_on = cast(:obsoletedOn as date), approved_by = cast(:approved as text), "
                        + "signature_note = cast(:signature as text), effective_on = cast(:effective as date), "
                        + "server_updated = cast(:server as text), master_list_updated = cast(:master as text), "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and "
                        + "request_id = :request and version = :version and deleted_at is null returning " + COLUMNS)
                .param("department", completed.department()).param("obsoleted", completed.obsoletedFormat())
                .param("obsoletedOn", day(completed.obsoletedEffectiveOn()))
                .param("approved", completed.approvedBy()).param("signature", completed.signatureNote())
                .param("effective", day(completed.effectiveOn())).param("server", completed.serverUpdated())
                .param("master", completed.masterListUpdated()).param("actor", actor).param("tenant", tenant())
                .param("request", requestId).param("version", version).query(RegisterStore::row).optional();
        window(WRITE, "off");
        return saved;
    }

    /**
     * Stamps the new revision and the moment of the publish, once, and fills what the approvers left blank: the
     * effective day, the two answers (both yes), the approvals and the signature words.
     */
    void stamp(UUID requestId, Stamp stamp, UUID actor) {
        window(WRITE, "on");
        window(RESULT, "on");
        jdbc.sql("update inspection_change_register set new_rev_no = :newRev, new_revision_at = now(), "
                        + "effective_on = coalesce(effective_on, cast(:effective as date)), "
                        + "server_updated = coalesce(server_updated, 'YES'), "
                        + "master_list_updated = coalesce(master_list_updated, 'YES'), "
                        + "approved_by = coalesce(approved_by, cast(:approved as text)), "
                        + "signature_note = coalesce(signature_note, cast(:signature as text)), "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and "
                        + "request_id = :request and deleted_at is null")
                .param("newRev", stamp.newRevNo()).param("effective", day(stamp.effectiveOn()))
                .param("approved", stamp.approvedBy()).param("signature", stamp.signatureNote())
                .param("actor", actor).param("tenant", tenant()).param("request", requestId).update();
        window(RESULT, "off");
        window(WRITE, "off");
    }

    private void window(String setting, String state) {
        jdbc.sql("select set_config(:setting, :state, true)").param("setting", setting).param("state", state)
                .query().listOfRows();
    }

    private static Date day(LocalDate day) {
        return day == null ? null : Date.valueOf(day);
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static RegisterRow row(ResultSet rs, int row) throws SQLException {
        Instant requestedAt = instant(rs, "requested_at");
        Instant newRevisionAt = instant(rs, "new_revision_at");
        RegisterValues values = new RegisterValues(rs.getString("request_no"), rs.getString("document_name"),
                rs.getString("document_no"), rs.getString("rev_no"),
                requestedAt == null ? null : requestedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                rs.getString("requested_by_name"), rs.getString("change_requested"), rs.getString("basis_for_change"),
                rs.getString("horizontal_deployment"), rs.getString("approved_by"), rs.getString("signature_note"),
                rs.getString("new_rev_no"), local(rs, "effective_on"),
                newRevisionAt == null ? null : newRevisionAt.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                rs.getString("department"), rs.getString("obsoleted_format"), local(rs, "obsoleted_effective_on"),
                rs.getString("server_updated"), rs.getString("master_list_updated"));
        return new RegisterRow(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                rs.getObject("template_id", UUID.class), values, newRevisionAt, rs.getLong("version"));
    }

    private static LocalDate local(ResultSet rs, String column) throws SQLException {
        Date day = rs.getDate(column);
        return day == null ? null : day.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
