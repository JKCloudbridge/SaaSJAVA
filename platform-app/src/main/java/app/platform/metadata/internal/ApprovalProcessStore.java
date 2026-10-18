package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about approval processes and their versions (ADR-0145). Like the rest of the module it runs under the
 * tenant context that was open when the transaction began, and every change carries the version it was based on. A
 * version row is written once and never changed (the database refuses an update).
 */
@Repository
class ApprovalProcessStore {

    private static final String CURRENT = "select p.id, v.id as version_id, p.object_api_name, p.api_name, p.label, "
            + "p.description, p.active, p.definition_version, v.entry_criteria, v.allow_self_approval, "
            + "v.steps::text as steps, v.outcomes::text as outcomes, v.fallback::text as fallback, p.version, "
            + "p.deleted_at is not null as removed "
            + "from approval_process p join approval_process_version v on v.process_id = p.id "
            + "and v.version_number = p.definition_version and v.deleted_at is null ";

    /**
     * A stored process in the definition it has.
     *
     * @param definitionVersion the number of the definition shown (the current one, or a pinned one)
     * @param steps the steps as JSON
     * @param outcomes the outcomes as JSON
     * @param fallback the fallback approver as JSON, or null
     * @param version the version of the process row
     * @param removed whether the process was removed
     */
    record Row(UUID id, UUID versionId, String objectApiName, String apiName, String label, String description,
            boolean active, int definitionVersion, String entryCriteria, boolean allowSelfApproval, String steps,
            String outcomes, String fallback, long version, boolean removed) {
    }

    private final JdbcClient jdbc;

    ApprovalProcessStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every live process in its current definition, grouped by object and in the order they were made. */
    List<Row> all() {
        return jdbc.sql(CURRENT + "where p.deleted_at is null order by lower(p.object_api_name), p.created_at, p.id")
                .query(ApprovalProcessStore::row).list();
    }

    Optional<Row> find(String objectApiName, String apiName) {
        return jdbc.sql(CURRENT + "where p.object_api_name = :object and p.api_name = :name and p.deleted_at is null")
                .param("object", objectApiName).param("name", apiName).query(ApprovalProcessStore::row).optional();
    }

    /**
     * One version of a process with the standing of the process now, also when the process was removed or the version
     * is
     * not the current one.
     */
    Optional<Row> version(UUID versionId) {
        return jdbc.sql("select p.id, v.id as version_id, p.object_api_name, p.api_name, p.label, p.description, "
                        + "p.active, v.version_number as definition_version, v.entry_criteria, v.allow_self_approval, "
                        + "v.steps::text as steps, v.outcomes::text as outcomes, v.fallback::text as fallback, "
                        + "p.version, p.deleted_at is not null as removed "
                        + "from approval_process_version v join approval_process p on p.id = v.process_id "
                        + "where v.id = :id and v.deleted_at is null")
                .param("id", versionId).query(ApprovalProcessStore::row).optional();
    }

    boolean nameTaken(String objectApiName, String apiName) {
        return jdbc.sql("select exists (select 1 from approval_process where lower(object_api_name) = lower(:object) "
                        + "and lower(api_name) = lower(:name) and deleted_at is null)")
                .param("object", objectApiName).param("name", apiName).query(Boolean.class).single();
    }

    int count(String objectApiName) {
        return jdbc.sql("select count(*) from approval_process where object_api_name = :object "
                        + "and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    /** Makes the process with its first definition. */
    Row insert(String objectApiName, String apiName, String label, String description, boolean active,
            String entryCriteria, boolean allowSelfApproval, String steps, String outcomes, String fallback,
            UUID actor) {
        UUID id = jdbc.sql("insert into approval_process (object_api_name, api_name, label, description, active, "
                        + "definition_version, created_by, updated_by) values (:object, :name, :label, "
                        + ":description, :active, 1, :actor, :actor) returning id")
                .param("object", objectApiName).param("name", apiName).param("label", label)
                .param("description", description).param("active", active).param("actor", actor)
                .query(UUID.class).single();
        insertVersion(id, 1, entryCriteria, allowSelfApproval, steps, outcomes, fallback, actor);
        return find(objectApiName, apiName).orElseThrow();
    }

    /** Writes a new definition of the process. @return the identifier of the new version */
    UUID insertVersion(UUID processId, int number, String entryCriteria, boolean allowSelfApproval, String steps,
            String outcomes, String fallback, UUID actor) {
        return jdbc.sql("insert into approval_process_version (process_id, version_number, entry_criteria, "
                        + "allow_self_approval, steps, outcomes, fallback, created_by, updated_by) values "
                        + "(:process, :number, :criteria, :self, cast(:steps as jsonb), cast(:outcomes as jsonb), "
                        + "cast(:fallback as jsonb), :actor, :actor) returning id")
                .param("process", processId).param("number", number).param("criteria", entryCriteria)
                .param("self", allowSelfApproval).param("steps", steps).param("outcomes", outcomes)
                .param("fallback", fallback).param("actor", actor).query(UUID.class).single();
    }

    /** Changes the process when it still has the version the caller read; false when it does not. */
    boolean update(UUID id, long version, String label, String description, boolean active, int definitionVersion,
            UUID actor) {
        return jdbc.sql("update approval_process set label = :label, description = :description, active = :active, "
                        + "definition_version = :definition, version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("label", label).param("description", description).param("active", active)
                .param("definition", definitionVersion).param("actor", actor).param("id", id)
                .param("version", version).update() == 1;
    }

    void delete(UUID id, UUID actor) {
        jdbc.sql("update approval_process set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    /** Removes old definitions that nothing names, at most one batch. @return how many were removed */
    int cleanVersions(Duration olderThan, int batch) {
        return jdbc.sql("select platform_approval_version_cleanup(make_interval(secs => "
                        + "cast(:seconds as double precision)), :batch)")
                .param("seconds", olderThan.toMillis() / 1000.0).param("batch", batch)
                .query(Integer.class).single();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getString("object_api_name"), rs.getString("api_name"), rs.getString("label"),
                rs.getString("description"), rs.getBoolean("active"), rs.getInt("definition_version"),
                rs.getString("entry_criteria"), rs.getBoolean("allow_self_approval"), rs.getString("steps"),
                rs.getString("outcomes"), rs.getString("fallback"), rs.getLong("version"),
                rs.getBoolean("removed"));
    }
}
