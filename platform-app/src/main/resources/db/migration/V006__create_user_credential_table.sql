-- Credentials of a user: the password hash and the sign-in protection state (ADR-0020, ADR-0021).
--
-- Platform-level for the same reason as platform_user (ADR-0022). The hash is the only secret-like value in the table;
-- it is written by the credential repository and read only by the sign-in path, never selected into a response, a log
-- line or an audit record. The hash text carries its own algorithm and parameters ($argon2id$v=19$m=...,t=...,p=...$),
-- so the parameters can change later and old hashes are upgraded at the next successful sign-in (ADR-0020).
--
-- The lock state lives here, in the database, on purpose: it must hold on every instance and must not depend on
-- Redis being up (decision of Sprint 3). Rate limits, which may be approximate, live in Redis.

create table user_credential (
    id                  uuid        primary key default uuidv7(),
    user_id             uuid        not null references platform_user (id),
    password_hash       text        not null,
    password_changed_at timestamptz not null default now(),
    failed_attempts     integer     not null default 0,
    last_failed_at      timestamptz,
    locked_until        timestamptz,
    last_sign_in_at     timestamptz,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint user_credential_hash_present check (char_length(password_hash) between 20 and 500),
    constraint user_credential_failed_attempts_not_negative check (failed_attempts >= 0)
);

create trigger user_credential_row_guard before insert or update on user_credential
    for each row execute function platform_row_guard();

-- A user has one live credential.
create unique index user_credential_user_live on user_credential (user_id) where deleted_at is null;

comment on table user_credential is
    'Password hash and sign-in lock state of a user. Platform-level (ADR-0022). The hash is never returned, logged or audited.';
