-- One-time link tokens for sign-up and password reset (Sprint 4, ADR-0023).
--
-- Platform-level on purpose (ADR-0023): a sign-up token is issued for an e-mail address that has no account and so no
-- user row and no tenant, and a reset happens on the platform host where there is no tenant. The table has no
-- tenant_id and no row level security. The tenant-related column that other tables carry does not exist here at all.
--
-- A token is 32 random bytes, URL-safe. Only its SHA-256 hash is stored, never the token: a copy of this table cannot
-- be used to complete a sign-up or a reset. The token is created at the moment the e-mail is sent (never earlier), so
-- the mail queue holds no secret either. A token is single use (used_at), expires (expires_at) and a newer token of
-- the same purpose for the same address cancels the older ones (revoked_at).

create table account_token (
    id         uuid        primary key default uuidv7(),
    purpose    text        not null,
    email      text        not null,
    user_id    uuid        references platform_user (id),
    token_hash text        not null,
    expires_at timestamptz not null,
    used_at    timestamptz,
    revoked_at timestamptz,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint account_token_purpose_known check (purpose in ('SIGN_UP', 'PASSWORD_RESET')),
    constraint account_token_hash_format check (token_hash ~ '^sha256:[0-9a-f]{64}$'),
    constraint account_token_reset_has_user check (purpose <> 'PASSWORD_RESET' or user_id is not null)
);

create trigger account_token_row_guard before insert or update on account_token
    for each row execute function platform_row_guard();

-- The lookup by what the person presents.
create unique index account_token_hash_live on account_token (token_hash) where deleted_at is null;
-- Cancelling the older tokens of one purpose for one address.
create index account_token_open on account_token (purpose, email) where used_at is null and revoked_at is null;
-- Cancelling the reset links of one user when the password changes.
create index account_token_user_open on account_token (user_id)
    where user_id is not null and used_at is null and revoked_at is null;
-- Retention: old tokens are removed after a while.
create index account_token_expires on account_token (expires_at);

comment on table account_token is
    'One-time sign-up and password-reset link tokens, stored only as hashes. Platform-level (ADR-0023).';
