package app.platform.identity.internal;

import app.platform.identity.PlatformPerson;
import app.platform.identity.PlatformRole;
import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The platform role assignments (ADR-0030). Platform-level: read and written without a tenant context. The database
 * refuses to remove the last platform administrator, also under concurrency.
 */
@Repository
class PlatformRoleRepository {

    private static final String PERSON_SELECT = "select a.id, a.user_id, u.email, u.display_name, a.role, "
            + "a.created_at from platform_role_assignment a join platform_user u on u.id = a.user_id "
            + "where a.deleted_at is null and u.deleted_at is null";

    private final JdbcClient jdbc;

    PlatformRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The roles a person holds now; those of an account that cannot sign in count for nothing. */
    Set<PlatformRole> rolesOf(UUID userId) {
        Set<PlatformRole> roles = EnumSet.noneOf(PlatformRole.class);
        jdbc.sql("select a.role from platform_role_assignment a join platform_user u on u.id = a.user_id "
                        + "where a.user_id = :user and a.deleted_at is null and u.status = 'ACTIVE' "
                        + "and u.deleted_at is null")
                .param("user", userId)
                .query(String.class)
                .list()
                .forEach(role -> roles.add(PlatformRole.valueOf(role)));
        return roles;
    }

    List<PlatformPerson> list() {
        return jdbc.sql(PERSON_SELECT + " order by a.created_at, a.id")
                .query(PlatformRoleRepository::person)
                .list();
    }

    Optional<PlatformPerson> find(UUID assignmentId) {
        return jdbc.sql(PERSON_SELECT + " and a.id = :id")
                .param("id", assignmentId)
                .query(PlatformRoleRepository::person)
                .optional();
    }

    /** Grants the role. A person who holds it already raises {@code DuplicateKeyException}. */
    UUID insert(UUID userId, PlatformRole role, ActorId actor) {
        return jdbc.sql("insert into platform_role_assignment (user_id, role, created_by, updated_by) "
                        + "values (:user, :role, :actor, :actor) returning id")
                .param("user", userId)
                .param("role", role.name())
                .param("actor", actor.value())
                .query(UUID.class)
                .single();
    }

    /** Ends the assignment (a soft delete). The database refuses it for the last platform administrator. */
    boolean remove(UUID assignmentId, ActorId actor) {
        return jdbc.sql("update platform_role_assignment set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor.value())
                .param("id", assignmentId)
                .update() == 1;
    }

    private static PlatformPerson person(ResultSet rs, int row) throws SQLException {
        return new PlatformPerson(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("email"), rs.getString("display_name"), PlatformRole.valueOf(rs.getString("role")),
                rs.getTimestamp("created_at").toInstant());
    }
}
