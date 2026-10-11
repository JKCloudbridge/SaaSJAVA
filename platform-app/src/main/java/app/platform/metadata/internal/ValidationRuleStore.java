package app.platform.metadata.internal;

import app.platform.metadata.ValidationRule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about validation rules (ADR-0078). Like the rest of the module it runs under the tenant context that
 * was open when the transaction began, and every change carries the version it was based on.
 */
@Repository
class ValidationRuleStore {

    private static final String COLUMNS = "id, object_api_name, api_name, label, description, expression, "
            + "error_message, error_field, active, version";

    /** A stored rule with its row identifier. */
    record Row(UUID id, ValidationRule rule) {
    }

    private final JdbcClient jdbc;

    ValidationRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every rule, grouped by object and in the order they were made. */
    List<ValidationRule> all() {
        return jdbc.sql("select " + COLUMNS + " from validation_rule where deleted_at is null "
                + "order by lower(object_api_name), created_at, id").query(ValidationRuleStore::rule).list();
    }

    Optional<Row> find(String objectApiName, String apiName) {
        return jdbc.sql("select " + COLUMNS + " from validation_rule where object_api_name = :object "
                        + "and api_name = :name and deleted_at is null")
                .param("object", objectApiName).param("name", apiName).query(ValidationRuleStore::row).optional();
    }

    boolean nameTaken(String objectApiName, String apiName) {
        return jdbc.sql("select exists (select 1 from validation_rule where lower(object_api_name) = lower(:object) "
                        + "and lower(api_name) = lower(:name) and deleted_at is null)")
                .param("object", objectApiName).param("name", apiName).query(Boolean.class).single();
    }

    int count(String objectApiName) {
        return jdbc.sql("select count(*) from validation_rule where object_api_name = :object "
                        + "and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    Row insert(String objectApiName, String apiName, String label, String description, String expression,
            String errorMessage, String errorField, boolean active, UUID actor) {
        return jdbc.sql("insert into validation_rule (object_api_name, api_name, label, description, expression, "
                        + "error_message, error_field, active, created_by, updated_by) values (:object, :name, "
                        + ":label, :description, :expression, :message, :field, :active, :actor, :actor) "
                        + "returning " + COLUMNS)
                .param("object", objectApiName).param("name", apiName).param("label", label)
                .param("description", description).param("expression", expression).param("message", errorMessage)
                .param("field", errorField).param("active", active).param("actor", actor)
                .query(ValidationRuleStore::row).single();
    }

    /** Changes the rule when it still has the version the caller read; false when it does not. */
    boolean update(UUID id, long version, String label, String description, String expression, String errorMessage,
            String errorField, boolean active, UUID actor) {
        return jdbc.sql("update validation_rule set label = :label, description = :description, "
                        + "expression = :expression, error_message = :message, error_field = :field, "
                        + "active = :active, version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("label", label).param("description", description).param("expression", expression)
                .param("message", errorMessage).param("field", errorField).param("active", active)
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    void delete(UUID id, UUID actor) {
        jdbc.sql("update validation_rule set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rule(rs, row));
    }

    private static ValidationRule rule(ResultSet rs, int row) throws SQLException {
        return new ValidationRule(rs.getString("object_api_name"), rs.getString("api_name"), rs.getString("label"),
                rs.getString("description"), rs.getString("expression"), rs.getString("error_message"),
                rs.getString("error_field"), rs.getBoolean("active"), rs.getLong("version"));
    }
}
