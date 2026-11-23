package app.platform.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two tables of connected applications (V095, ADR-0267, ADR-0268). Tenant-scoped: every statement runs under the
 * tenant context that was open when the transaction began, and row level security refuses anything else, so an
 * application is found only on the host of the organization that registered it. The organization is never passed in.
 * Only this class names the tables {@code oauth_client} and {@code oauth_client_secret}
 * ({@code ClientsArchitectureTest}); no statement is built from a typed text, and nothing here ever holds a secret,
 * only the hash of one.
 */
@Repository
class ClientStore {

    /**
     * An application, with what the list needs to say whether it runs.
     *
     * @param actsAsName the display name of the member it acts as, or null when the person has none
     * @param userStatus the status of that person's account
     * @param membershipStatus the status of their membership of the organization
     * @param activeSecrets how many of its secrets work now
     * @param lastUsedAt the latest use of any of its secrets
     */
    record ClientRow(UUID id, String clientId, String name, String description, UUID membershipId, UUID userId,
            String state, List<String> scopes, List<String> objects, int tokenSeconds, long version,
            Instant createdAt, String actsAsName, String userStatus, String membershipStatus, int activeSecrets,
            Instant lastUsedAt) {

        boolean active() {
            return "ACTIVE".equals(state);
        }

        boolean memberCanAct() {
            return "ACTIVE".equals(userStatus) && MembershipRepository.ACTIVE.equals(membershipStatus);
        }
    }

    /** One secret, as a hash and its marks. */
    record SecretRow(UUID id, UUID clientRowId, int number, String hash, Instant expiresAt, Instant endedAt,
            String endedReason, Instant lastUsedAt, Instant createdAt) {

        /** Whether the secret works at the given moment. */
        boolean works(Instant at) {
            return endedAt == null && (expiresAt == null || expiresAt.isAfter(at));
        }

        /** The hash is never printed. */
        @Override
        public String toString() {
            return "SecretRow[" + id + "]";
        }
    }

    private static final String CLIENT_COLUMNS = "c.id, c.client_id, c.name, c.description, c.membership_id, "
            + "c.user_id, c.state, array_to_string(c.scopes, ',') as scopes, "
            + "array_to_string(c.objects, ',') as objects, c.token_seconds, c.version, c.created_at, "
            + "u.display_name, u.status as user_status, m.status as membership_status, "
            + "(select count(*) from oauth_client_secret s where s.client_row_id = c.id and s.deleted_at is null "
            + "and s.ended_at is null and (s.expires_at is null or s.expires_at > now())) as active_secrets, "
            + "(select max(s.last_used_at) from oauth_client_secret s where s.client_row_id = c.id "
            + "and s.deleted_at is null) as last_used_at";

    private static final String CLIENT_FROM = " from oauth_client c join platform_user u on u.id = c.user_id "
            + "join membership m on m.id = c.membership_id ";

    private final JdbcClient jdbc;

    ClientStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- applications ----

    int count() {
        Integer count = jdbc.sql("select count(*) from oauth_client where deleted_at is null")
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    UUID insert(String clientId, String name, String description, UUID membershipId, UUID userId, List<String> scopes,
            List<String> objects, int tokenSeconds, UUID actor) {
        return jdbc.sql("insert into oauth_client (client_id, name, description, membership_id, user_id, scopes, "
                        + "objects, token_seconds, created_by, updated_by) values (:clientId, :name, :description, "
                        + ":membership, :user, string_to_array(:scopes, ','), "
                        + "case when cast(:objects as text) is null then null "
                        + "else string_to_array(:objects, ',') end, :seconds, :actor, :actor) returning id")
                .param("clientId", clientId)
                .param("name", name)
                .param("description", description)
                .param("membership", membershipId)
                .param("user", userId)
                .param("scopes", String.join(",", scopes))
                .param("objects", objects == null ? null : String.join(",", objects), Types.VARCHAR)
                .param("seconds", tokenSeconds)
                .param("actor", actor)
                .query(UUID.class)
                .single();
    }

    List<ClientRow> list() {
        return jdbc.sql("select " + CLIENT_COLUMNS + CLIENT_FROM
                        + "where c.deleted_at is null order by c.created_at desc, c.id")
                .query(ClientStore::client)
                .list();
    }

    Optional<ClientRow> find(UUID id) {
        return jdbc.sql("select " + CLIENT_COLUMNS + CLIENT_FROM + "where c.id = :id and c.deleted_at is null")
                .param("id", id)
                .query(ClientStore::client)
                .optional();
    }

    /** The application with the public identifier, on the host of its organization only. */
    Optional<ClientRow> findByClientId(String clientId) {
        return jdbc.sql("select " + CLIENT_COLUMNS + CLIENT_FROM
                        + "where c.client_id = :clientId and c.deleted_at is null")
                .param("clientId", clientId)
                .query(ClientStore::client)
                .optional();
    }

    /** Takes the row for the rest of the transaction, so that changes to one application are made one at a time. */
    boolean lock(UUID id) {
        return jdbc.sql("select id from oauth_client where id = :id and deleted_at is null for update")
                .param("id", id).query(UUID.class).optional().isPresent();
    }

    /** @return whether the application had the version the change was based on */
    boolean update(UUID id, long version, String name, String description, List<String> scopes, List<String> objects,
            int tokenSeconds, UUID actor) {
        return jdbc.sql("update oauth_client set name = :name, description = :description, "
                        + "scopes = string_to_array(:scopes, ','), "
                        + "objects = case when cast(:objects as text) is null then null "
                        + "else string_to_array(:objects, ',') end, token_seconds = :seconds, "
                        + "updated_by = :actor, version = version + 1 "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("name", name)
                .param("description", description)
                .param("scopes", String.join(",", scopes))
                .param("objects", objects == null ? null : String.join(",", objects), Types.VARCHAR)
                .param("seconds", tokenSeconds)
                .param("actor", actor)
                .param("id", id)
                .param("version", version)
                .update() == 1;
    }

    void setState(UUID id, String state, UUID actor) {
        jdbc.sql("update oauth_client set state = :state, updated_by = :actor, version = version + 1 "
                        + "where id = :id and deleted_at is null")
                .param("state", state)
                .param("actor", actor)
                .param("id", id)
                .update();
    }

    void markRemoved(UUID id, UUID actor) {
        jdbc.sql("update oauth_client set deleted_at = now(), deleted_by = :actor, updated_by = :actor, "
                        + "version = version + 1 where id = :id and deleted_at is null")
                .param("actor", actor)
                .param("id", id)
                .update();
    }

    // ---- secrets ----

    UUID insertSecret(UUID clientRowId, String hash, UUID actor) {
        return jdbc.sql("insert into oauth_client_secret (client_row_id, number, secret_hash, created_by, "
                        + "updated_by) select :client, coalesce(max(number), 0) + 1, :hash, :actor, :actor "
                        + "from oauth_client_secret where client_row_id = :client returning id")
                .param("client", clientRowId)
                .param("hash", hash)
                .param("actor", actor)
                .query(UUID.class)
                .single();
    }

    /** The secrets of an application, newest first. */
    List<SecretRow> secretsOf(UUID clientRowId) {
        return jdbc.sql("select id, client_row_id, number, secret_hash, expires_at, ended_at, ended_reason, "
                        + "last_used_at, created_at from oauth_client_secret where client_row_id = :client "
                        + "and deleted_at is null order by number desc")
                .param("client", clientRowId)
                .query(ClientStore::secret)
                .list();
    }

    Optional<SecretRow> findSecret(UUID clientRowId, UUID secretId) {
        return jdbc.sql("select id, client_row_id, number, secret_hash, expires_at, ended_at, ended_reason, "
                        + "last_used_at, created_at from oauth_client_secret where client_row_id = :client "
                        + "and id = :id and deleted_at is null")
                .param("client", clientRowId)
                .param("id", secretId)
                .query(ClientStore::secret)
                .optional();
    }

    /** The secrets that work now. */
    List<SecretRow> workingSecrets(UUID clientRowId) {
        return jdbc.sql("select id, client_row_id, number, secret_hash, expires_at, ended_at, ended_reason, "
                        + "last_used_at, created_at from oauth_client_secret where client_row_id = :client "
                        + "and deleted_at is null and ended_at is null and (expires_at is null or expires_at > now()) "
                        + "order by number desc")
                .param("client", clientRowId)
                .query(ClientStore::secret)
                .list();
    }

    void endSecret(UUID secretId, String reason, UUID actor) {
        jdbc.sql("update oauth_client_secret set ended_at = now(), ended_reason = :reason, updated_by = :actor, "
                        + "version = version + 1 where id = :id and ended_at is null")
                .param("reason", reason)
                .param("actor", actor)
                .param("id", secretId)
                .update();
    }

    /** Ends every working secret of an application. */
    void endSecretsOf(UUID clientRowId, String reason, UUID actor) {
        jdbc.sql("update oauth_client_secret set ended_at = now(), ended_reason = :reason, updated_by = :actor, "
                        + "version = version + 1 where client_row_id = :client and ended_at is null")
                .param("reason", reason)
                .param("actor", actor)
                .param("client", clientRowId)
                .update();
    }

    /** Lets an older secret work until the given time (an overlap after a rotation). */
    void setSecretEnd(UUID secretId, Instant endsAt, UUID actor) {
        jdbc.sql("update oauth_client_secret set expires_at = :ends, updated_by = :actor, version = version + 1 "
                        + "where id = :id and ended_at is null")
                .param("ends", OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC))
                .param("actor", actor)
                .param("id", secretId)
                .update();
    }

    /** Writes "last used" on a secret. */
    void touchSecret(UUID secretId, UUID actor) {
        jdbc.sql("update oauth_client_secret set last_used_at = now(), updated_by = :actor, version = version + 1 "
                        + "where id = :id")
                .param("actor", actor)
                .param("id", secretId)
                .update();
    }

    // ---- mapping ----

    private static ClientRow client(ResultSet rs, int row) throws SQLException {
        return new ClientRow(
                rs.getObject("id", UUID.class),
                rs.getString("client_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("membership_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("state"),
                split(rs.getString("scopes")),
                rs.getString("objects") == null ? null : split(rs.getString("objects")),
                rs.getInt("token_seconds"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("display_name"),
                rs.getString("user_status"),
                rs.getString("membership_status"),
                rs.getInt("active_secrets"),
                instant(rs, "last_used_at"));
    }

    private static SecretRow secret(ResultSet rs, int row) throws SQLException {
        return new SecretRow(
                rs.getObject("id", UUID.class),
                rs.getObject("client_row_id", UUID.class),
                rs.getInt("number"),
                rs.getString("secret_hash"),
                instant(rs, "expires_at"),
                instant(rs, "ended_at"),
                rs.getString("ended_reason"),
                instant(rs, "last_used_at"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : List.copyOf(Arrays.asList(joined.split(",")));
    }
}
