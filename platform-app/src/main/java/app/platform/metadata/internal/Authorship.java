package app.platform.metadata.internal;

import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platformapi.ApiException;
import app.platformapi.AuthorshipView;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who made each definition of an object and who last changed it (UA3, ADR-0169). Every definition row already carries
 * the base columns {@code created_by}, {@code created_at}, {@code updated_by} and {@code updated_at} (ADR-0010), so
 * this reads them back; it stores nothing and changes no other view.
 *
 * <p>A name is shown only for a person who is an active member of the organization (the {@code User} directory of the
 * identity module answers, so no other module is read), and the platform's own actor is shown as {@code Platform}. A
 * person who left gets no name, and the answer does not say why. The standard definitions of the platform are files,
 * not rows, so they have no entry; the screens say the platform made them.
 */
@Component
class Authorship {

    private static final UUID PLATFORM_ACTOR = new UUID(0L, 0L);
    private static final String PLATFORM = "Platform";

    /** The six tables share the base columns; the table names are constants here and never come from a request. */
    private static final String SQL = """
            select 'OBJECT' as kind, api_name, created_by, created_at, updated_by, updated_at
              from object_definition where lower(api_name) = lower(:object) and deleted_at is null
            union all
            select 'FIELD', api_name, created_by, created_at, updated_by, updated_at
              from field_definition where object_api_name = :object and deleted_at is null
            union all
            select 'RECORD_TYPE', api_name, created_by, created_at, updated_by, updated_at
              from record_type where object_api_name = :object and deleted_at is null
            union all
            select 'LAYOUT', api_name, created_by, created_at, updated_by, updated_at
              from page_layout where object_api_name = :object and deleted_at is null
            union all
            select 'RECORD_PAGE', api_name, created_by, created_at, updated_by, updated_at
              from record_page where object_api_name = :object and deleted_at is null
            union all
            select 'VALIDATION_RULE', api_name, created_by, created_at, updated_by, updated_at
              from validation_rule where object_api_name = :object and deleted_at is null
            order by 1, 4, 2
            """;

    private final JdbcClient jdbc;
    private final MetadataCatalogue catalogue;
    private final List<ReferenceLabels> directories;

    Authorship(JdbcClient jdbc, MetadataCatalogue catalogue, List<ReferenceLabels> directories) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.directories = directories;
    }

    private record Row(String kind, String apiName, UUID createdBy, Instant createdAt, UUID updatedBy,
            Instant updatedAt) {
    }

    List<AuthorshipView> of(String objectApiName) {
        ObjectDefinition object = catalogue.snapshot().object(objectApiName)
                .orElseThrow(() -> ApiException.notFound("This object does not exist."));
        List<Row> rows = jdbc.sql(SQL).param("object", object.apiName()).query((rs, n) -> new Row(
                rs.getString("kind"), rs.getString("api_name"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("updated_by", UUID.class),
                rs.getTimestamp("updated_at").toInstant())).list();
        Set<UUID> people = new HashSet<>();
        for (Row row : rows) {
            people.add(row.createdBy());
            people.add(row.updatedBy());
        }
        people.remove(PLATFORM_ACTOR);
        Map<UUID, String> names = people.isEmpty() ? Map.of() : directories.stream()
                .filter(directory -> "User".equals(directory.object())).findFirst()
                .map(directory -> directory.labels(people)).orElse(Map.of());
        return rows.stream().map(row -> new AuthorshipView(row.kind(), row.apiName(), name(row.createdBy(), names),
                row.createdAt(), name(row.updatedBy(), names), row.updatedAt())).toList();
    }

    private static String name(UUID actor, Map<UUID, String> names) {
        return PLATFORM_ACTOR.equals(actor) ? PLATFORM : names.get(actor);
    }
}
