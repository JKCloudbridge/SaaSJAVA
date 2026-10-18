package app.platform.security.internal;

import app.platform.licensing.Licences;
import app.platform.security.ApproverDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Answers {@link ApproverDirectory} from the current rows of the access tables (ADR-0146); no cache, because an
 * approval
 * step asks once when it becomes active and must see the people who hold the profile or role at that moment. Each
 * question is one narrow statement plus the profiles and the licences held, so no question loads every member.
 */
@Service
class DefaultApproverDirectory implements ApproverDirectory {

    private static final String BY_PROFILE = "select ma.membership_id, ma.user_id, ma.profile_id from member_access ma "
            + "where ma.profile_id = :profile and ma.deleted_at is null order by ma.membership_id";
    private static final String BY_ROLE = "select ma.membership_id, ma.user_id, ma.profile_id from member_access ma "
            + "where ma.role_id = :role and ma.deleted_at is null order by ma.membership_id";
    private static final String PARENT_HOLDERS = "select ma.membership_id, ma.user_id, ma.profile_id "
            + "from member_access ma "
            + "where ma.role_id = (select r.parent_id from security_role r join member_access mine "
            + "on mine.role_id = r.id and mine.user_id = :user and mine.deleted_at is null where r.deleted_at is null "
            + "limit 1) and ma.deleted_at is null order by ma.membership_id";
    private static final String BY_USERS = "select ma.membership_id, ma.user_id, ma.profile_id from member_access ma "
            + "where ma.user_id = any(cast(:users as uuid[])) and ma.deleted_at is null order by ma.membership_id";
    private static final String BY_MEMBERSHIPS = "select ma.membership_id, ma.user_id, ma.profile_id "
            + "from member_access ma where ma.membership_id = any(cast(:memberships as uuid[])) "
            + "and ma.deleted_at is null order by ma.membership_id";

    private record Row(UUID membershipId, UUID userId, UUID profileId) {
    }

    private final JdbcClient jdbc;
    private final AccessStore store;
    private final Licences licences;
    private final AccessWork work;
    private final GroupStore groupStore;

    DefaultApproverDirectory(JdbcClient jdbc, AccessStore store, Licences licences, AccessWork work,
            GroupStore groupStore) {
        this.jdbc = jdbc;
        this.groupStore = groupStore;
        this.store = store;
        this.licences = licences;
        this.work = work;
    }

    @Override
    public List<Named> profiles() {
        return work.run(() -> store.profiles().stream().map(profile -> new Named(profile.id(), profile.name()))
                .toList());
    }

    @Override
    public List<Named> roles() {
        return work.run(() -> store.roles().stream().map(role -> new Named(role.id(), role.name())).toList());
    }

    @Override
    public List<Named> groups() {
        return work.run(() -> groupStore.groups().stream().map(group -> new Named(group.id(), group.name()))
                .toList());
    }

    @Override
    public List<Candidate> holdersOfProfile(UUID profileId) {
        if (profileId == null) {
            return List.of();
        }
        return work.run(() -> candidates(jdbc.sql(BY_PROFILE).param("profile", profileId)
                .query(DefaultApproverDirectory::row)
                .list()));
    }

    @Override
    public List<Candidate> holdersOfRole(UUID roleId) {
        if (roleId == null) {
            return List.of();
        }
        return work.run(() -> candidates(jdbc.sql(BY_ROLE).param("role", roleId).query(DefaultApproverDirectory::row)
                .list()));
    }

    @Override
    public List<Candidate> parentRoleHolders(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return work.run(() -> candidates(jdbc.sql(PARENT_HOLDERS).param("user", userId)
                .query(DefaultApproverDirectory::row).list()));
    }

    @Override
    public List<Candidate> ofUsers(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return work.run(() -> candidates(jdbc.sql(BY_USERS).param("users", array(userIds))
                .query(DefaultApproverDirectory::row).list()));
    }

    @Override
    public List<Candidate> ofMemberships(Collection<UUID> membershipIds) {
        if (membershipIds == null || membershipIds.isEmpty()) {
            return List.of();
        }
        return work.run(() -> candidates(jdbc.sql(BY_MEMBERSHIPS).param("memberships", array(membershipIds))
                .query(DefaultApproverDirectory::row).list()));
    }

    /** Adds the licence fact: the member holds the licence type their profile needs. */
    private List<Candidate> candidates(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> typeKeys = licences.licenceTypeKeys();
        Map<UUID, String> held = licences.assigned();
        Map<UUID, String> neededByProfile = new HashMap<>();
        store.profiles().forEach(profile -> neededByProfile.put(profile.id(), typeKeys.get(profile.licenceTypeId())));
        return rows.stream().map(row -> {
            String needed = neededByProfile.get(row.profileId());
            boolean licensed = needed != null && needed.equals(held.get(row.membershipId()));
            return new Candidate(row.membershipId(), row.userId(), licensed);
        }).collect(Collectors.toList());
    }

    private static String array(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("membership_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("profile_id", UUID.class));
    }
}
