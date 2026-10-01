-- Authorization records: one row per sign-in grant, holding the issued tokens (ADR-0019).
--
-- The row is the "family" of tokens that came from one sign-in: the authorization code, the access token, the ID token
-- and the refresh token that is replaced on every use. Revoking the row revokes them all. The row is indexed by user,
-- so "sign out everywhere" and the future administrative session list are one indexed query (story S3-SEC-17).
--
-- The column layout of the first block follows the table the authorization server library documents, because the
-- platform reuses the library's own JSON mapping of the grant (attributes and token metadata). Two differences, both
-- on purpose:
--   * Every value that works as a bearer secret (code, access token, refresh token, ID token) is stored only as its
--     SHA-256 hash, prefixed "sha256:". A copy of this table, a backup or a read-only SQL account cannot be used to
--     act as a signed-in user. Lookups hash the presented value first.
--   * The first block's blob columns are text and its timestamps are timestamptz, as the library's PostgreSQL note says.
--
-- The second block belongs to the platform: the owning user, the organization host the grant is bound to (null: the
-- platform host), the user's security version at issue, the previous refresh token's hash (to recognise a replay of a
-- rotated token, story S3-SEC-13) and the revocation mark. bound_tenant_id is not called tenant_id on purpose: the
-- table is platform-level and the schema-conventions test treats a tenant_id column as "tenant data" (ADR-0022).

create table oauth2_authorization (
    id                            uuid        primary key default uuidv7(),

    registered_client_id          text        not null,
    principal_name                text        not null,
    authorization_grant_type      text        not null,
    authorized_scopes             text,
    attributes                    text,
    state                         text,
    authorization_code_value      text,
    authorization_code_issued_at  timestamptz,
    authorization_code_expires_at timestamptz,
    authorization_code_metadata   text,
    access_token_value            text,
    access_token_issued_at        timestamptz,
    access_token_expires_at       timestamptz,
    access_token_metadata         text,
    access_token_type             text,
    access_token_scopes           text,
    oidc_id_token_value           text,
    oidc_id_token_issued_at       timestamptz,
    oidc_id_token_expires_at      timestamptz,
    oidc_id_token_metadata        text,
    refresh_token_value           text,
    refresh_token_issued_at       timestamptz,
    refresh_token_expires_at      timestamptz,
    refresh_token_metadata        text,
    user_code_value               text,
    user_code_issued_at           timestamptz,
    user_code_expires_at          timestamptz,
    user_code_metadata            text,
    device_code_value             text,
    device_code_issued_at         timestamptz,
    device_code_expires_at        timestamptz,
    device_code_metadata          text,

    user_id                       uuid        not null references platform_user (id),
    bound_tenant_id               uuid        references tenant (id),
    security_version              bigint      not null,
    previous_refresh_token_hash   text,
    refresh_rotated_at            timestamptz,
    revoked_at                    timestamptz,
    revoked_reason                text,

    version                       bigint      not null default 0,
    created_at                    timestamptz not null default now(),
    created_by                    uuid        not null,
    updated_at                    timestamptz not null default now(),
    updated_by                    uuid        not null,
    deleted_at                    timestamptz,
    deleted_by                    uuid
);

create trigger oauth2_authorization_row_guard before insert or update on oauth2_authorization
    for each row execute function platform_row_guard();

create index oauth2_authorization_user on oauth2_authorization (user_id);
create unique index oauth2_authorization_code_live
    on oauth2_authorization (authorization_code_value)
    where deleted_at is null and authorization_code_value is not null;
create unique index oauth2_authorization_access_live
    on oauth2_authorization (access_token_value)
    where deleted_at is null and access_token_value is not null;
create unique index oauth2_authorization_refresh_live
    on oauth2_authorization (refresh_token_value)
    where deleted_at is null and refresh_token_value is not null;
create unique index oauth2_authorization_id_token_live
    on oauth2_authorization (oidc_id_token_value)
    where deleted_at is null and oidc_id_token_value is not null;
create index oauth2_authorization_previous_refresh
    on oauth2_authorization (previous_refresh_token_hash) where previous_refresh_token_hash is not null;
create index oauth2_authorization_state
    on oauth2_authorization (state) where state is not null;

comment on table oauth2_authorization is
    'One row per sign-in grant and its tokens, stored as hashes, indexed by user. Platform-level (ADR-0022).';
