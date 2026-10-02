package app.platform.identity.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where the authorization server keeps its grants (table oauth2_authorization, ADR-0019). One row is one sign-in grant
 * and the whole family of tokens that came from it: the authorization code, the access token, the ID token and the
 * refresh token that is replaced on every use. It implements the library's storage contract and adds what the platform
 * needs on top of it:
 *
 * <ul>
 *   <li><b>Only hashes of secrets are stored</b> (code, access token, refresh token, ID token): a copy of the table
 * cannot       be used to act as a user. A presented value is hashed before the lookup.</li>
 *   <li><b>Immediate revocation</b>: a grant is found only while it is alive, which is decided in the same query that
 *       finds it: not revoked, not past its absolute lifetime, its user may sign in, and the user's security version is
 *       still the one the grant was issued under. There is no cached copy that could be stale on another instance.</li>
 *   <li><b>Bound to the host</b> it was issued on (an organization, or the platform host): presented on another host
 * it is       refused. Since Sprint 5 the membership is checked as well (every request, and a membership that
 * ends revokes the grants bound to its organization).</li>
 *   <li><b>Refresh rotation with reuse detection</b> (story S3-SEC-13): every use of a refresh token replaces it and
 * keeps       the hash of the one replaced. A replayed replaced token revokes the whole grant, unless it was replaced
 * only a       moment ago (two browser tabs refreshing together), which is refused without punishment.</li>
 *   <li><b>One winner per refresh</b>: the new refresh token is stored only if the stored one is still the one the
 * request       started from, so two simultaneous refreshes cannot both succeed and leave the browser with a dead
 * token.</li>   <li>An index by user for "end everything of this user" and the future administrative session list
 *       (story S3-SEC-17).</li>
 * </ul>
 */
@Repository
class AuthorizationStore implements OAuth2AuthorizationService {

    /** Standard columns, in the order the library's parameter mapper produces them (after the id). */
    private static final List<String> STANDARD_COLUMNS = List.of(
            "registered_client_id", "principal_name", "authorization_grant_type", "authorized_scopes", "attributes",
            "state",
            "authorization_code_value", "authorization_code_issued_at", "authorization_code_expires_at",
            "authorization_code_metadata",
            "access_token_value", "access_token_issued_at", "access_token_expires_at", "access_token_metadata",
            "access_token_type", "access_token_scopes",
            "oidc_id_token_value", "oidc_id_token_issued_at", "oidc_id_token_expires_at", "oidc_id_token_metadata",
            "refresh_token_value", "refresh_token_issued_at", "refresh_token_expires_at", "refresh_token_metadata",
            "user_code_value", "user_code_issued_at", "user_code_expires_at", "user_code_metadata",
            "device_code_value", "device_code_issued_at", "device_code_expires_at", "device_code_metadata");

    /** Positions (in {@link #STANDARD_COLUMNS}) of the values that work as bearer secrets and are stored hashed. */
    private static final Set<String> SECRET_COLUMNS = Set.of(
            "authorization_code_value", "access_token_value", "oidc_id_token_value", "refresh_token_value");

    private static final String ALIVE = "a.deleted_at is null and a.revoked_at is null and u.deleted_at is null "
            + "and u.status = 'ACTIVE' and u.security_version = a.security_version "
            + "and a.created_at + make_interval(secs => :absolute) > now()";

    /**
     * A grant found by the library, with what the platform adds to it.
     *
     * @param authorization the library's view of the grant
     * @param userId the user
     * @param boundTenantId the organization host it is bound to, null for the platform host
     */
    private record Found(OAuth2Authorization authorization, UUID userId, UUID boundTenantId) {
    }

    /** What the request that is refreshing a token started from, to keep two simultaneous refreshes apart. */
    private record LoadedRefresh(UUID authorizationId, String refreshHash) {
    }

    /**
     * A valid access token, as the resource-server side sees it.
     *
     * @param authorizationId the grant
     * @param userId the user
     * @param scopes the granted scopes
     * @param expiresAt when the access token ends
     * @param startedAt when the grant (the sign-in) began; it does not move when tokens are refreshed
     */
    record Introspected(UUID authorizationId, UUID userId, Set<String> scopes, Instant expiresAt,
            Instant startedAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final TenantContexts contexts;
    private final AuthAudit audit;
    private final IdentityProperties.Tokens tokens;
    private final JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper parameters =
            new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper();
    private final JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper rows;
    private final ThreadLocal<LoadedRefresh> loadedRefresh = new ThreadLocal<>();
    // Set when a refresh was refused only because another request replaced the token a moment ago; read once.
    private final ThreadLocal<Boolean> benignRefusal = new ThreadLocal<>();

    AuthorizationStore(JdbcClient jdbc, JdbcOperations jdbcOperations, TransactionTemplate transaction,
            RegisteredClientRepository clients, TenantContexts contexts, AuthAudit audit,
            IdentityProperties properties) {
        // The library's mappers learn the column types of the table from a one-time look at it, which its own JDBC
        // service does when it is constructed. That service is not used for anything else here: building it is how the
        // mappers are initialised (the table has the library's documented layout for PostgreSQL).
        new JdbcOAuth2AuthorizationService(jdbcOperations, clients);
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.contexts = contexts;
        this.audit = audit;
        this.tokens = properties.tokens();
        this.rows = new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(clients);
    }

    // ---- the library's contract ----

    @Override
    public void save(OAuth2Authorization authorization) {
        UUID id = UUID.fromString(authorization.getId());
        UUID userId = UUID.fromString(authorization.getPrincipalName());
        List<SqlParameterValue> values = new ArrayList<>(parameters.apply(authorization));
        // values.get(0) is the id, then the standard columns in order.
        for (int i = 0; i < STANDARD_COLUMNS.size(); i++) {
            if (SECRET_COLUMNS.contains(STANDARD_COLUMNS.get(i))) {
                Object raw = values.get(i + 1).getValue();
                if (raw != null) {
                    values.set(i + 1, new SqlParameterValue(Types.VARCHAR, Hashes.stored(text(raw))));
                }
            }
        }
        boolean tokenInvalidated = invalidated(authorization.getAccessToken())
                || invalidated(authorization.getRefreshToken());
        LoadedRefresh loaded = loadedRefresh.get();
        loadedRefresh.remove();
        transaction.executeWithoutResult(status -> {
            Optional<String[]> existing = jdbc.sql("select coalesce(refresh_token_value, ''), "
                            + "coalesce(access_token_value, '') from oauth2_authorization where id = :id for update")
                    .param("id", id)
                    .query((rs, row) -> new String[] {rs.getString(1), rs.getString(2)})
                    .optional();
            if (existing.isEmpty()) {
                insert(id, userId, values);
                return;
            }
            String currentRefresh = existing.get()[0];
            String currentAccess = existing.get()[1];
            if (loaded != null && loaded.authorizationId().equals(id) && !currentRefresh.equals(loaded.refreshHash())) {
                // Another request replaced the refresh token after this one read it: only one refresh can win.
                benignRefusal.set(Boolean.TRUE);
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
            }
            update(id, userId, values, currentRefresh, tokenInvalidated);
            String newAccess = hashedValue(values, "access_token_value");
            String newRefresh = hashedValue(values, "refresh_token_value");
            if (currentAccess.isEmpty() && !newAccess.isEmpty()) {
                audit.tokenIssued(userId, authorization.getAuthorizationGrantType().getValue(),
                        authorization.getRegisteredClientId());
            } else if (!currentRefresh.isEmpty() && !newRefresh.isEmpty() && !currentRefresh.equals(newRefresh)) {
                audit.tokenRefreshed(userId);
            }
        });
    }

    private static String hashedValue(List<SqlParameterValue> values, String column) {
        Object value = values.get(STANDARD_COLUMNS.indexOf(column) + 1).getValue();
        return value == null ? "" : text(value);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        jdbc.sql("delete from oauth2_authorization where id = :id")
                .param("id", UUID.fromString(authorization.getId()))
                .update();
    }

    @Override
    public OAuth2Authorization findById(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return find("a.id = :value", uuid).map(Found::authorization).orElse(null);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String type = tokenType == null ? "" : tokenType.getValue();
        Optional<Found> found = switch (type) {
            case OAuth2ParameterNames.ACCESS_TOKEN -> find("a.access_token_value = :value", Hashes.stored(token));
            case OAuth2ParameterNames.REFRESH_TOKEN -> findRefresh(token);
            case OAuth2ParameterNames.CODE -> find("a.authorization_code_value = :value", Hashes.stored(token));
            case "id_token" -> find("a.oidc_id_token_value = :value", Hashes.stored(token));
            case OAuth2ParameterNames.STATE -> find("a.state = :value", token);
            default -> findInAnyColumn(token);
        };
        return found.filter(this::boundToThisHost).map(Found::authorization).orElse(null);
    }

    // ---- what the platform adds ----

    /**
     * Whether the refresh that was just refused on this thread was refused only because the token had been replaced a
     * moment ago (two tabs refreshing together): such a caller is not signed out, it simply tries again with the
     * cookies the other request set. Reading it clears it.
     */
    boolean consumeBenignRefusal() {
        boolean benign = Boolean.TRUE.equals(benignRefusal.get());
        benignRefusal.remove();
        return benign;
    }

    /** The valid access token behind a presented value, for the check made on every API request. */
    Optional<Introspected> introspect(String accessToken) {
        Optional<Introspected> found = jdbc.sql("select a.id, a.user_id, a.bound_tenant_id, a.access_token_scopes, "
                        + "a.access_token_expires_at, a.created_at from oauth2_authorization a "
                        + "join platform_user u on u.id = a.user_id "
                        + "where a.access_token_value = :value and a.access_token_expires_at > now() and " + ALIVE)
                .param("value", Hashes.stored(accessToken))
                .param("absolute", (double) tokens.refreshAbsolute().toSeconds())
                .query((rs, row) -> new Introspected(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        scopes(rs.getString("access_token_scopes")),
                        rs.getObject("access_token_expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
        if (found.isEmpty()) {
            return found;
        }
        UUID bound = jdbc.sql("select bound_tenant_id from oauth2_authorization where id = :id")
                .param("id", found.get().authorizationId())
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (!sameHost(bound)) {
            audit.bindingRefused(found.get().userId(), "access_token");
            return Optional.empty();
        }
        return found;
    }

    /** Revokes every grant of a user: all their tokens stop working now. @return how many grants were alive */
    int revokeAll(UUID userId, String reason, UUID actor) {
        return jdbc.sql("update oauth2_authorization set revoked_at = now(), revoked_reason = :reason, "
                        + "updated_by = :actor, version = version + 1 "
                        + "where user_id = :user and revoked_at is null and deleted_at is null")
                .param("reason", reason)
                .param("actor", actor)
                .param("user", userId)
                .update();
    }

    /**
     * Revokes every grant of a user that is bound to one organization's host: all their tokens for that organization
     * stop working now, and the person's other organizations are untouched. @return how many grants were alive
     */
    int revokeAllIn(UUID userId, UUID boundTenantId, String reason, UUID actor) {
        return jdbc.sql("update oauth2_authorization set revoked_at = now(), revoked_reason = :reason, "
                        + "updated_by = :actor, version = version + 1 "
                        + "where user_id = :user and bound_tenant_id = :tenant and revoked_at is null "
                        + "and deleted_at is null")
                .param("reason", reason)
                .param("actor", actor)
                .param("user", userId)
                .param("tenant", boundTenantId)
                .update();
    }

    /** One live grant of a user, without any token: when it began, how long it can last, which host it is bound to. */
    record GrantSummary(Instant started, Instant lastsUntil, UUID boundTenantId) {
    }

    /** The live grants of a user (no token, no hash), newest first. */
    List<GrantSummary> liveGrantsOf(UUID userId) {
        return jdbc.sql("select a.created_at, a.bound_tenant_id from oauth2_authorization a "
                        + "join platform_user u on u.id = a.user_id where a.user_id = :user and " + ALIVE
                        + " order by a.created_at desc")
                .param("user", userId)
                .param("absolute", (double) tokens.refreshAbsolute().toSeconds())
                .query((rs, row) -> {
                    Instant started = rs.getObject("created_at", OffsetDateTime.class).toInstant();
                    return new GrantSummary(started, started.plus(tokens.refreshAbsolute()),
                            rs.getObject("bound_tenant_id", UUID.class));
                })
                .list();
    }

    /**
     * Revokes every grant bound to one organization's host, except those of {@code keepUser} (may be null).
     *
     * @return how many grants were alive
     */
    int revokeAllOfOrganization(UUID boundTenantId, UUID keepUser, String reason, UUID actor) {
        return jdbc.sql("update oauth2_authorization set revoked_at = now(), revoked_reason = :reason, "
                        + "updated_by = :actor, version = version + 1 "
                        + "where bound_tenant_id = :tenant and revoked_at is null and deleted_at is null "
                        + "and (cast(:keep as uuid) is null or user_id <> cast(:keep as uuid))")
                .param("reason", reason)
                .param("actor", actor)
                .param("tenant", boundTenantId)
                .param("keep", keepUser, java.sql.Types.OTHER)
                .update();
    }

    /**
     * Revokes the grant a refresh token belongs to (sign-out with only the refresh cookie left).
     *
     * @return the user of the grant, or empty when no live grant has this refresh token
     */
    Optional<UUID> revokeByRefreshToken(String refreshToken, String reason) {
        return jdbc.sql("update oauth2_authorization set revoked_at = now(), revoked_reason = :reason, "
                        + "updated_by = user_id, version = version + 1 "
                        + "where refresh_token_value = :value and revoked_at is null and deleted_at is null "
                        + "returning user_id")
                .param("reason", reason)
                .param("value", Hashes.stored(refreshToken))
                .query(UUID.class)
                .optional();
    }

    /** Revokes one grant. @return whether it was alive */
    boolean revoke(UUID authorizationId, String reason, UUID actor) {
        return jdbc.sql("update oauth2_authorization set revoked_at = now(), revoked_reason = :reason, "
                        + "updated_by = :actor, version = version + 1 "
                        + "where id = :id and revoked_at is null and deleted_at is null")
                .param("reason", reason)
                .param("actor", actor)
                .param("id", authorizationId)
                .update() == 1;
    }

    /**
     * Removes grants that ended before {@code keepEnded} ago: revoked ones, and ones past their absolute lifetime.
     *
     * @return how many
     */
    int purgeEnded(java.time.Duration keepEnded) {
        return jdbc.sql("delete from oauth2_authorization where "
                        + "(revoked_at is not null and revoked_at < now() - make_interval(secs => :keep)) or "
                        + "(created_at + make_interval(secs => :absolute) < now() - make_interval(secs => :keep))")
                .param("keep", (double) keepEnded.toSeconds())
                .param("absolute", (double) tokens.refreshAbsolute().toSeconds())
                .update();
    }

    // ---- internals ----

    private Optional<Found> findRefresh(String token) {
        String hash = Hashes.stored(token);
        benignRefusal.remove();
        Optional<Found> current = find("a.refresh_token_value = :value", hash);
        if (current.isPresent()) {
            loadedRefresh.set(new LoadedRefresh(UUID.fromString(current.get().authorization().getId()), hash));
            return current;
        }
        loadedRefresh.remove();
        detectReuse(hash);
        return Optional.empty();
    }

    /** A refresh token that was replaced earlier is presented again. */
    private void detectReuse(String hash) {
        jdbc.sql("select id, user_id, refresh_rotated_at from oauth2_authorization "
                        + "where previous_refresh_token_hash = :hash and revoked_at is null and deleted_at is null")
                .param("hash", hash)
                .query((rs, row) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("refresh_rotated_at", OffsetDateTime.class)})
                .optional()
                .ifPresent(row -> {
                    UUID id = (UUID) row[0];
                    UUID userId = (UUID) row[1];
                    OffsetDateTime rotatedAt = (OffsetDateTime) row[2];
                    boolean justReplaced = rotatedAt != null
                            && rotatedAt.toInstant().plus(tokens.refreshGrace()).isAfter(Instant.now());
                    if (justReplaced) {
                        benignRefusal.set(Boolean.TRUE);
                        audit.tokenRefused(userId, "refresh_replaced_moments_ago");
                    } else if (revoke(id, "refresh_reuse", userId)) {
                        audit.refreshReuseDetected(userId);
                    }
                });
    }

    private Optional<Found> findInAnyColumn(String token) {
        for (String column : List.of("access_token_value", "refresh_token_value", "authorization_code_value",
                "oidc_id_token_value")) {
            Optional<Found> found = find("a." + column + " = :value", Hashes.stored(token));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<Found> find(String condition, Object value) {
        RowMapper<Found> mapper = (rs, row) -> new Found(
                rows.mapRow(rs, row),
                rs.getObject("user_id", UUID.class),
                rs.getObject("bound_tenant_id", UUID.class));
        return jdbc.sql("select a.* from oauth2_authorization a join platform_user u on u.id = a.user_id where "
                        + condition + " and " + ALIVE)
                .param("value", value)
                .param("absolute", (double) tokens.refreshAbsolute().toSeconds())
                .query(mapper)
                .optional();
    }

    private boolean boundToThisHost(Found found) {
        if (sameHost(found.boundTenantId())) {
            return true;
        }
        audit.bindingRefused(found.userId(), "grant");
        return false;
    }

    /** Whether the host of the current request is the one a grant was issued on (null: the platform host). */
    private boolean sameHost(UUID boundTenantId) {
        UUID current = contexts.current().map(TenantContext::tenantId).map(tenant -> tenant.value()).orElse(null);
        return java.util.Objects.equals(boundTenantId, current);
    }

    private void insert(UUID id, UUID userId, List<SqlParameterValue> values) {
        UUID tenant = contexts.current().map(TenantContext::tenantId).map(t -> t.value()).orElse(null);
        String columns = String.join(", ", STANDARD_COLUMNS);
        String placeholders = ":p" + String.join(", :p", indexes(STANDARD_COLUMNS.size()));
        JdbcClient.StatementSpec statement = jdbc.sql("insert into oauth2_authorization (id, " + columns
                + ", user_id, bound_tenant_id, security_version, created_by, updated_by) values (:id, " + placeholders
                + ", :user, :tenant, (select security_version from platform_user where id = :user), :user, :user)")
                .param("id", id)
                .param("user", userId)
                .param("tenant", tenant, Types.OTHER);
        bind(statement, values).update();
    }

    private void update(UUID id, UUID userId, List<SqlParameterValue> values, String currentRefresh,
            boolean tokenInvalidated) {
        StringBuilder set = new StringBuilder();
        for (int i = 0; i < STANDARD_COLUMNS.size(); i++) {
            set.append(STANDARD_COLUMNS.get(i)).append(" = :p").append(i).append(", ");
        }
        String newRefresh = hashedValue(values, "refresh_token_value");
        boolean rotated = !currentRefresh.isEmpty() && !newRefresh.isEmpty() && !currentRefresh.equals(newRefresh);
        JdbcClient.StatementSpec statement = jdbc.sql("update oauth2_authorization set " + set
                + (rotated ? "previous_refresh_token_hash = :previous, refresh_rotated_at = now(), " : "")
                + (tokenInvalidated
                        ? "revoked_at = coalesce(revoked_at, now()), revoked_reason = coalesce(revoked_reason, "
                                + "'token_invalidated'), "
                        : "")
                + "updated_by = :user, version = version + 1 where id = :id")
                .param("id", id)
                .param("user", userId);
        if (rotated) {
            statement = statement.param("previous", currentRefresh);
        }
        bind(statement, values).update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, List<SqlParameterValue> values) {
        JdbcClient.StatementSpec bound = statement;
        for (int i = 0; i < STANDARD_COLUMNS.size(); i++) {
            SqlParameterValue value = values.get(i + 1);
            Object converted = convert(value);
            bound = converted == null
                    ? bound.param("p" + i, null, Types.NULL)
                    : bound.param("p" + i, converted);
        }
        return bound;
    }

    /** The library describes blobs as bytes and times as timestamps; the table holds text and timestamptz. */
    private static Object convert(SqlParameterValue value) {
        Object raw = value.getValue();
        if (raw == null) {
            return null;
        }
        return switch (value.getSqlType()) {
            case Types.BLOB, Types.VARBINARY, Types.BINARY, Types.LONGVARBINARY -> text(raw);
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> timestamp(raw);
            default -> raw;
        };
    }

    private static String text(Object raw) {
        return raw instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : raw.toString();
    }

    private static OffsetDateTime timestamp(Object raw) {
        if (raw instanceof java.sql.Timestamp timestamp) {
            return OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
        }
        if (raw instanceof Instant instant) {
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        return (OffsetDateTime) raw;
    }

    private static List<String> indexes(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Integer.toString(i));
        }
        return list;
    }

    private static boolean invalidated(OAuth2Authorization.Token<?> token) {
        return token != null && token.isInvalidated();
    }

    private static Set<String> scopes(String text) {
        return text == null || text.isBlank() ? Set.of() : Set.of(text.split(","));
    }
}
