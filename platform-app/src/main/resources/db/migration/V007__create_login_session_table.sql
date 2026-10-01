-- Short-lived sign-in sessions (ADR-0019).
--
-- After a successful password check the browser holds an opaque cookie that proves the sign-in for a few minutes, only
-- long enough for the authorization-code step of the sign-in to finish. It is a row, not a server memory session, so
-- every instance sees it and sign-out revokes it everywhere. The cookie value is a random secret; only its SHA-256
-- hash is stored, so a copy of this table cannot be used to sign in.
--
-- bound_tenant_id is the organization host the user signed in on (null: the platform host). It is deliberately not
-- called tenant_id: this table is platform-level, the column records where the session may be used, and the
-- schema-conventions test treats a tenant_id column as "tenant data, needs row level security" (ADR-0022).

create table login_session (
    id               uuid        primary key default uuidv7(),
    token_hash       text        not null,
    user_id          uuid        not null references platform_user (id),
    bound_tenant_id  uuid        references tenant (id),
    security_version bigint      not null,
    expires_at       timestamptz not null,
    revoked_at       timestamptz,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid
);

create trigger login_session_row_guard before insert or update on login_session
    for each row execute function platform_row_guard();

create unique index login_session_token_live on login_session (token_hash) where deleted_at is null;
create index login_session_user on login_session (user_id);
create index login_session_expiry on login_session (expires_at);

comment on table login_session is
    'Short-lived sign-in sessions; only a hash of the cookie value is stored. Platform-level (ADR-0022).';
