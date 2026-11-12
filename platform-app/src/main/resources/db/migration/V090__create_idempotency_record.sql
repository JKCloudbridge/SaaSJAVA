-- The memory of keyed writes of the public API (Sprint 30, ADR-0246). Expand only; nothing is removed or renamed.
--
--   idempotency_record  One row for each keyed write that was carried out: who asked (the member), a hash of the key they
--                       chose, a hash of the request, what kind of write it was, and the outcome as identifiers only
--                       (the record that was made and its version, or for a bulk call the status, identifier and version
--                       of each position and the code of each refusal). It holds no value of any record and no word a
--                       person typed: a repeat of the request is answered by reading the record again.
--
-- The row is written in the same transaction as the write it describes, so it exists exactly when the write was
-- committed. A caller that lost the answer and sends the same key and the same request therefore finds the row and
-- gets the same outcome instead of a second record; the same key with another request, or another kind of write, is
-- a conflict and is told so. A row is good for the retention period, 24 hours by default; after that the key is free
-- again, and the clean-up removes the row.
--
-- What must hold even if the application had a bug:
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * one row for each organization, member and key hash (unique index), so a key means one thing for one person;
--   * the key and the request are only ever hashes (shape checks), the outcome is bounded in size;
--   * a row never changes after it is written (update trigger) and is removed only after it has expired (delete
--     trigger), whoever asks, the owner of the schema included.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. Nothing in it is ever written to a log, an audit record or an event.

create table idempotency_record (
    id             uuid        primary key default uuidv7(),
    tenant_id      uuid        not null default platform_current_tenant() references tenant (id),
    caller_id      uuid        not null,
    key_hash       text        not null,
    request_hash   text        not null,
    operation      text        not null,
    object_api_name text       not null,
    outcome        jsonb       not null,
    expires_at     timestamptz not null,
    version        bigint      not null default 0,
    created_at     timestamptz not null default now(),
    created_by     uuid        not null,
    updated_at     timestamptz not null default now(),
    updated_by     uuid        not null,
    deleted_at     timestamptz,
    deleted_by     uuid,
    constraint idempotency_record_key_shape check (key_hash ~ '^[0-9a-f]{64}$'),
    constraint idempotency_record_request_shape check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint idempotency_record_operation check (operation in ('CREATE_RECORD', 'BULK_RECORDS')),
    constraint idempotency_record_object_shape check (object_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,79}$'),
    constraint idempotency_record_outcome_size check (pg_column_size(outcome) <= 262144),
    constraint idempotency_record_expiry check (expires_at > created_at)
);

-- A key means one thing for one member of one organization (the index also leads with the tenant).
create unique index idempotency_record_key
    on idempotency_record (tenant_id, caller_id, key_hash) where deleted_at is null;
-- The clean-up reads the oldest expired rows of an organization first.
create index idempotency_record_expiry_order
    on idempotency_record (tenant_id, expires_at) where deleted_at is null;

create trigger idempotency_record_row_guard before insert or update on idempotency_record
    for each row execute function platform_row_guard();
create trigger idempotency_record_tenant_guard before update on idempotency_record
    for each row execute function platform_tenant_guard();

-- The life of a row, held at the database whatever the application does (ADR-0246): written once, never changed,
-- removed only when it has expired. The application removes an expired row of the same key when a key is used again.
create function platform_idempotency_record_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        raise exception 'idempotency guard: a recorded outcome never changes'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'DELETE' then
        if old.expires_at > now() then
            raise exception 'idempotency guard: a recorded outcome is kept until it has expired'
                using errcode = 'check_violation';
        end if;
        return old;
    end if;
    return new;
end;
$guard$;

create trigger idempotency_record_life_guard before update or delete on idempotency_record
    for each row execute function platform_idempotency_record_guard();

alter table idempotency_record enable row level security;
alter table idempotency_record force row level security;
create policy idempotency_record_insert on idempotency_record for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy idempotency_record_select on idempotency_record for select
    using (tenant_id = (select platform_current_tenant()));
-- There is no update policy: a row is never changed.
create policy idempotency_record_delete on idempotency_record for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table idempotency_record is
    'The outcome of a keyed write of the public API, as identifiers only, kept for the retention period so that a repeat of the request makes no second record (ADR-0246).';
