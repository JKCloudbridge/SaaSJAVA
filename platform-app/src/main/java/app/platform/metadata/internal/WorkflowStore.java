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
 * Every statement about workflows and their versions (ADR-0136). Like the rest of the module it runs under the tenant
 * context that was open when the transaction began, and every change carries the version it was based on. A version row
 * is written once and never changed (the database refuses an update).
 */
@Repository
class WorkflowStore {

    private static final String CURRENT = "select w.id, v.id as version_id, w.object_api_name, w.api_name, w.label, "
            + "w.description, w.active, w.definition_version, v.trigger::text as trigger, v.condition, "
            + "v.actions::text as actions, w.version, w.deleted_at is not null as removed "
            + "from workflow w join workflow_version v on v.workflow_id = w.id "
            + "and v.version_number = w.definition_version and v.deleted_at is null ";

    /**
     * A stored workflow in the definition it has.
     *
     * @param definitionVersion the number of the definition shown (the current one, or a pinned one)
     * @param trigger the trigger as JSON
     * @param actions the actions as JSON
     * @param version the version of the workflow row
     * @param removed whether the workflow was removed
     */
    record Row(UUID id, UUID versionId, String objectApiName, String apiName, String label, String description,
            boolean active, int definitionVersion, String trigger, String condition, String actions, long version,
            boolean removed) {
    }

    private final JdbcClient jdbc;

    WorkflowStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every live workflow in its current definition, grouped by object and in the order they were made. */
    List<Row> all() {
        return jdbc.sql(CURRENT + "where w.deleted_at is null order by lower(w.object_api_name), w.created_at, w.id")
                .query(WorkflowStore::row).list();
    }

    Optional<Row> find(String objectApiName, String apiName) {
        return jdbc.sql(CURRENT + "where w.object_api_name = :object and w.api_name = :name and w.deleted_at is null")
                .param("object", objectApiName).param("name", apiName).query(WorkflowStore::row).optional();
    }

    /**
     * One version of a workflow with the standing of the workflow now, also when the workflow was removed or the
     * version is not the current one.
     */
    Optional<Row> version(UUID versionId) {
        return jdbc.sql("select w.id, v.id as version_id, w.object_api_name, w.api_name, w.label, w.description, "
                        + "w.active, v.version_number as definition_version, v.trigger::text as trigger, "
                        + "v.condition, v.actions::text as actions, w.version, w.deleted_at is not null as removed "
                        + "from workflow_version v join workflow w on w.id = v.workflow_id "
                        + "where v.id = :id and v.deleted_at is null")
                .param("id", versionId).query(WorkflowStore::row).optional();
    }

    boolean nameTaken(String objectApiName, String apiName) {
        return jdbc.sql("select exists (select 1 from workflow where lower(object_api_name) = lower(:object) "
                        + "and lower(api_name) = lower(:name) and deleted_at is null)")
                .param("object", objectApiName).param("name", apiName).query(Boolean.class).single();
    }

    int count(String objectApiName) {
        return jdbc.sql("select count(*) from workflow where object_api_name = :object and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    /** How many before-save automations the object has (ADR-0160). */
    int countBeforeSave(String objectApiName) {
        return jdbc.sql("select count(*) from workflow w join workflow_version v on v.workflow_id = w.id "
                        + "and v.version_number = w.definition_version and v.deleted_at is null "
                        + "where w.object_api_name = :object and w.deleted_at is null "
                        + "and v.trigger_kind in ('BEFORE_CREATE', 'BEFORE_UPDATE')")
                .param("object", objectApiName).query(Integer.class).single();
    }

    /** Makes the workflow with its first definition. */
    Row insert(String objectApiName, String apiName, String label, String description, boolean active, String trigger,
            String condition, String actions, UUID actor) {
        UUID id = jdbc.sql("insert into workflow (object_api_name, api_name, label, description, active, "
                        + "definition_version, created_by, updated_by) values (:object, :name, :label, "
                        + ":description, :active, 1, :actor, :actor) returning id")
                .param("object", objectApiName).param("name", apiName).param("label", label)
                .param("description", description).param("active", active).param("actor", actor)
                .query(UUID.class).single();
        insertVersion(id, 1, trigger, condition, actions, actor);
        return find(objectApiName, apiName).orElseThrow();
    }

    /** Writes a new definition of the workflow. @return the identifier of the new version */
    UUID insertVersion(UUID workflowId, int number, String trigger, String condition, String actions, UUID actor) {
        return jdbc.sql("insert into workflow_version (workflow_id, version_number, trigger_kind, trigger, condition, "
                        + "actions, created_by, updated_by) values (:workflow, :number, "
                        + "(cast(:trigger as jsonb) ->> 'kind'), cast(:trigger as jsonb), :condition, "
                        + "cast(:actions as jsonb), :actor, :actor) returning id")
                .param("workflow", workflowId).param("number", number).param("trigger", trigger)
                .param("condition", condition).param("actions", actions).param("actor", actor)
                .query(UUID.class).single();
    }

    /** Changes the workflow when it still has the version the caller read; false when it does not. */
    boolean update(UUID id, long version, String label, String description, boolean active, int definitionVersion,
            UUID actor) {
        return jdbc.sql("update workflow set label = :label, description = :description, active = :active, "
                        + "definition_version = :definition, version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("label", label).param("description", description).param("active", active)
                .param("definition", definitionVersion).param("actor", actor).param("id", id)
                .param("version", version).update() == 1;
    }

    void delete(UUID id, UUID actor) {
        jdbc.sql("update workflow set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    /** Removes old definitions that nothing names, at most one batch. @return how many were removed */
    int cleanVersions(Duration olderThan, int batch) {
        return jdbc.sql("select platform_workflow_version_cleanup(make_interval(secs => "
                        + "cast(:seconds as double precision)), :batch)")
                .param("seconds", olderThan.toMillis() / 1000.0).param("batch", batch)
                .query(Integer.class).single();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getString("object_api_name"), rs.getString("api_name"), rs.getString("label"),
                rs.getString("description"), rs.getBoolean("active"), rs.getInt("definition_version"),
                rs.getString("trigger"), rs.getString("condition"), rs.getString("actions"), rs.getLong("version"),
                rs.getBoolean("removed"));
    }
}
