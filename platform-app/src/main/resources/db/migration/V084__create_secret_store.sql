-- The secrets store of an organization (Sprint 27, ADR-0212 to ADR-0215).
--
-- A secret is a named entry with numbered versions. A version holds the value ONLY as envelope-encrypted bytes: a fresh
-- data key encrypts the value, a key-encryption key that the deployment supplies (never a table, never this
-- repository) wraps the data key, and both ciphertexts are bound to the secret's own address (scope, name, version) as
-- authenticated associated data, so a row copied to another secret or another organization cannot be opened. Plaintext
-- does not exist in the database, so no SQL, backup or dump can read it.
--
-- What the tables never hold: a plaintext value, a key-encryption key, a hash or a fingerprint of a value. What they do
-- hold that is sensitive: ciphertext and wrapped keys, which is why the guards below make a stored version immutable.
--
-- A version is never edited: the only update is the one-time mark of its first use, and (under a setting that only the
-- store raises) the re-wrapping of its data key under the active key-encryption key. A version is deleted only when its
-- secret is removed or when the store prunes versions older than the retained few. Expand only, plus the widening of
-- one check constraint of the connection table (a test result of a connection may now be "voided", ADR-0213).

create table secret_entry (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    name            text        not null,
    current_version integer     not null default 1,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint secret_entry_name_shape check (name ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    constraint secret_entry_current_positive check (current_version >= 1)
);

create unique index secret_entry_tenant_name on secret_entry (tenant_id, name) where deleted_at is null;

create table secret_version (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    secret_id       uuid        not null references secret_entry (id) on delete cascade,
    ordinal         integer     not null,
    algorithm       text        not null,
    key_id          text        not null,
    wrapped_key     bytea       not null,
    sealed_value    bytea       not null,
    first_used_at   timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint secret_version_ordinal_positive check (ordinal >= 1),
    constraint secret_version_algorithm check (algorithm in ('A256GCM-1')),
    constraint secret_version_key_id_shape check (key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    -- nonce 12 + data key 32 + tag 16
    constraint secret_version_wrapped_size check (octet_length(wrapped_key) = 60),
    -- nonce 12 + at least one byte + tag 16, and a bound well above the longest value the application accepts
    constraint secret_version_sealed_size check (octet_length(sealed_value) between 29 and 20000)
);

create unique index secret_version_tenant_secret_ordinal on secret_version (tenant_id, secret_id, ordinal)
    where deleted_at is null;

-- One row per secret and clock hour: the counted summary of use (ADR-0213). Counts only; no value, no caller.
create table secret_usage (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    secret_id       uuid        not null references secret_entry (id) on delete cascade,
    hour_start      timestamptz not null,
    uses            integer     not null default 0,
    failures        integer     not null default 0,
    summarised      boolean     not null default false,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint secret_usage_counts check (uses >= 0 and failures >= 0)
);

create unique index secret_usage_tenant_secret_hour on secret_usage (tenant_id, secret_id, hour_start)
    where deleted_at is null;

create function platform_secret_entry_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        if new.name is distinct from old.name then
            raise exception 'secret guard: the name of a secret is immutable' using errcode = 'check_violation';
        end if;
        if new.current_version < old.current_version then
            raise exception 'secret guard: the current version of a secret only moves forward'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

-- A stored version is immutable (ADR-0212). Messages name the table only, never a value.
create function platform_secret_version_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.first_used_at is not null then
            raise exception 'secret guard: a version starts unused' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if tg_op = 'UPDATE' then
        if new.secret_id is distinct from old.secret_id or new.ordinal is distinct from old.ordinal
                or new.algorithm is distinct from old.algorithm
                or new.sealed_value is distinct from old.sealed_value then
            raise exception 'secret guard: a stored version is immutable' using errcode = 'check_violation';
        end if;
        if new.key_id is distinct from old.key_id or new.wrapped_key is distinct from old.wrapped_key then
            -- Re-wrapping the data key under the active key-encryption key is the one change of the key material.
            if current_setting('platform.secret_rewrap', true) is distinct from 'on' then
                raise exception 'secret guard: the key material of a version is immutable'
                    using errcode = 'check_violation';
            end if;
        end if;
        if old.first_used_at is not null and new.first_used_at is distinct from old.first_used_at then
            raise exception 'secret guard: the first use of a version is recorded once' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- delete
    if current_setting('platform.secret_erase', true) is distinct from 'on' then
        raise exception 'secret guard: a version is removed only with its secret or by pruning'
            using errcode = 'check_violation';
    end if;
    return old;
end;
$guard$;

create trigger secret_entry_row_guard before insert or update on secret_entry
    for each row execute function platform_row_guard();
create trigger secret_entry_tenant_guard before update on secret_entry
    for each row execute function platform_tenant_guard();
create trigger secret_entry_lifecycle_guard before insert or update on secret_entry
    for each row execute function platform_secret_entry_guard();

create trigger secret_version_row_guard before insert or update on secret_version
    for each row execute function platform_row_guard();
create trigger secret_version_tenant_guard before update on secret_version
    for each row execute function platform_tenant_guard();
create trigger secret_version_lifecycle_guard before insert or update or delete on secret_version
    for each row execute function platform_secret_version_guard();

create trigger secret_usage_row_guard before insert or update on secret_usage
    for each row execute function platform_row_guard();
create trigger secret_usage_tenant_guard before update on secret_usage
    for each row execute function platform_tenant_guard();

alter table secret_entry enable row level security;
alter table secret_entry force row level security;
create policy secret_entry_insert on secret_entry for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy secret_entry_select on secret_entry for select
    using (tenant_id = (select platform_current_tenant()));
create policy secret_entry_update on secret_entry for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy secret_entry_delete on secret_entry for delete
    using (tenant_id = (select platform_current_tenant()));

alter table secret_version enable row level security;
alter table secret_version force row level security;
create policy secret_version_insert on secret_version for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy secret_version_select on secret_version for select
    using (tenant_id = (select platform_current_tenant()));
create policy secret_version_update on secret_version for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy secret_version_delete on secret_version for delete
    using (tenant_id = (select platform_current_tenant()));

alter table secret_usage enable row level security;
alter table secret_usage force row level security;
create policy secret_usage_insert on secret_usage for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy secret_usage_select on secret_usage for select
    using (tenant_id = (select platform_current_tenant()));
create policy secret_usage_update on secret_usage for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy secret_usage_delete on secret_usage for delete
    using (tenant_id = (select platform_current_tenant()));

-- A test of a connection can be voided by a change of its credential (ADR-0213).
alter table integration_connection drop constraint integration_connection_test_result;
alter table integration_connection add constraint integration_connection_test_result
    check (last_test_result is null or last_test_result in ('PASSED', 'FAILED', 'VOIDED'));

comment on table secret_entry is
    'A named secret of one organization (Sprint 27). The value is never here: see secret_version.';
comment on table secret_version is
    'One version of a secret: envelope-encrypted bytes bound to the secret''s address. Immutable; no plaintext exists in the database (ADR-0212).';
comment on table secret_usage is
    'Counted use of a secret per clock hour: counts only (ADR-0213).';
