-- Files attached to records (Sprint 17, ADR-0120, ADR-0121): the metadata in PostgreSQL, the bytes in S3-compatible
-- object storage under a key that starts with the organization and never contains a name a person typed.
--
--   record_file           One row per file of a record: the name a person gave it (cleaned), the type the server
--                         decided, the size and the SHA-256 of the bytes it stored, the key of the bytes. PENDING while
--                         the bytes are being stored, ACTIVE once they are. The row is created before the first byte is
--                         written, so there is never a byte in the store that no row knows about. A file belongs to a
--                         record and goes with it: a record removed for good takes its rows (the foreign key); a record
--                         in the recycle bin keeps them and they come back with it (nobody can reach a file of a
--                         record they cannot reach).
--   file_object_cleanup   The keys of bytes that have to be removed from the store, one row each. A trigger writes a
--                         row whenever a row of record_file disappears, whatever the reason (a person removes the file,
--                         the record is removed for good, a failed upload is cleaned up), in the same transaction. A
--                         background job removes the bytes and then the row; the removal is repeatable, so a crash
--                         between the two steps costs nothing. The metadata is therefore gone before the bytes, and an
--                         orphan can never be reached through the application.
--
-- What must hold even if the application had a bug:
--   * a file row names a record of its own organization and object (checked on every write, because a foreign key check
--     bypasses row level security), and its key, its record and its object never change;
--   * the key of the bytes is unique, so two rows never share bytes and removing one cannot remove another's.

create table record_file (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    record_id       uuid        not null references object_record (id) on delete cascade,
    name            text        not null,
    content_type    text        not null,
    size_bytes      bigint      not null default 0,
    sha256          text,
    storage_key     text        not null,
    state           text        not null default 'PENDING',
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint record_file_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_file_name_length check (char_length(name) between 1 and 255),
    constraint record_file_type_length check (char_length(content_type) between 3 and 100),
    constraint record_file_size_positive check (size_bytes >= 0),
    constraint record_file_state_known check (state in ('PENDING', 'ACTIVE')),
    constraint record_file_sha256_shape check (sha256 is null or sha256 ~ '^[0-9a-f]{64}$'),
    constraint record_file_key_shape check (storage_key ~ '^files/[0-9a-f-]{36}/[0-9a-f-]{36}$')
);

create unique index record_file_storage_key on record_file (storage_key) where deleted_at is null;
create index record_file_tenant_record on record_file (tenant_id, record_id) where deleted_at is null;
-- The job that expires uploads that never finished.
create index record_file_pending on record_file (tenant_id, created_at) where state = 'PENDING' and deleted_at is null;

create function platform_record_file_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.object_api_name is distinct from old.object_api_name
            or new.record_id is distinct from old.record_id
            or new.storage_key is distinct from old.storage_key) then
        raise exception 'file guard: a file does not move to another record or to other bytes'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'UPDATE' and old.state = 'ACTIVE' and new.state = 'PENDING' then
        raise exception 'file guard: a stored file does not become pending again' using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if not exists (select 1 from object_record r where r.id = new.record_id and r.tenant_id = new.tenant_id
                          and r.object_api_name = new.object_api_name) then
            raise exception 'file guard: a file belongs to a record of this organization and object'
                using errcode = 'check_violation';
        end if;
        if new.storage_key not like 'files/' || new.tenant_id::text || '/%' then
            raise exception 'file guard: the bytes of a file are kept under the key of its organization'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_record_file_guard() is
    'A file names a record of its own organization and object, keeps its bytes under its organization''s key and never moves (ADR-0120).';

create trigger record_file_row_guard before insert or update on record_file
    for each row execute function platform_row_guard();
create trigger record_file_tenant_guard before update on record_file
    for each row execute function platform_tenant_guard();
create trigger record_file_use_guard before insert or update on record_file
    for each row execute function platform_record_file_guard();

alter table record_file enable row level security;
alter table record_file force row level security;
create policy record_file_insert on record_file for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy record_file_select on record_file for select
    using (tenant_id = (select platform_current_tenant()));
create policy record_file_update on record_file for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy record_file_delete on record_file for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table record_file is
    'A file attached to a record: its metadata. The bytes are in object storage under storage_key. Tenant-scoped, row level security forced (ADR-0120).';

-- ---------------------------------------------------------------------------------------------------------------
-- file_object_cleanup
-- ---------------------------------------------------------------------------------------------------------------

create table file_object_cleanup (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    storage_key text        not null,
    attempts    integer     not null default 0,
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint file_object_cleanup_key_shape check (storage_key ~ '^files/[0-9a-f-]{36}/[0-9a-f-]{36}$'),
    constraint file_object_cleanup_attempts_positive check (attempts >= 0)
);

create unique index file_object_cleanup_key on file_object_cleanup (storage_key) where deleted_at is null;
create index file_object_cleanup_tenant on file_object_cleanup (tenant_id, created_at) where deleted_at is null;

create trigger file_object_cleanup_row_guard before insert or update on file_object_cleanup
    for each row execute function platform_row_guard();
create trigger file_object_cleanup_tenant_guard before update on file_object_cleanup
    for each row execute function platform_tenant_guard();

alter table file_object_cleanup enable row level security;
alter table file_object_cleanup force row level security;
create policy file_object_cleanup_insert on file_object_cleanup for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy file_object_cleanup_select on file_object_cleanup for select
    using (tenant_id = (select platform_current_tenant()));
create policy file_object_cleanup_update on file_object_cleanup for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy file_object_cleanup_delete on file_object_cleanup for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table file_object_cleanup is
    'Keys of bytes in object storage that are to be removed because their file row is gone. Written by a trigger of record_file, drained by a job. Tenant-scoped, row level security forced (ADR-0121).';

-- Whenever a file row disappears, its bytes are queued for removal in the same transaction.
create function platform_record_file_queue_cleanup() returns trigger
    language plpgsql
as $queue$
begin
    insert into file_object_cleanup (tenant_id, storage_key, created_by, updated_by)
        values (old.tenant_id, old.storage_key, '00000000-0000-0000-0000-000000000000',
                '00000000-0000-0000-0000-000000000000')
        on conflict (storage_key) where deleted_at is null do nothing;
    return null;
end;
$queue$;

comment on function platform_record_file_queue_cleanup() is
    'Queues the bytes of a file for removal from object storage when its row is deleted, for whatever reason (ADR-0121).';

create trigger record_file_queue_cleanup after delete on record_file
    for each row execute function platform_record_file_queue_cleanup();
