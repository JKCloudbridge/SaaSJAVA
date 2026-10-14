-- CSV imports (Sprint 17, ADR-0123): the job that reads a file, row by row, and creates or updates records through the
-- same pipeline as any write, with the permissions of the member who started it.
--
--   import_job     One row per import: what to do (create, or upsert by an external identifier), the mapping of the
--                  file's columns to fields, the state, the counts and where it stands in the file (the byte offset
--                  after the last row that was committed), so that a job stopped by a crash or a restart carries on
--                  from the same place and no row is done twice. The file itself and the report of the rows that failed
--                  are in object storage under keys that start with the organization (never a name a person typed),
--                  and are removed after a retention period. At most one import is queued or running per object.
--   import_error   The rows that failed, with the number of the row, the reason (the message of the write pipeline,
--                  which never repeats a value) and the row as the file had it, so that the person can correct and
--                  send it again. Removed with the file.
--
-- What must hold even if the application had a bug:
--   * only one import of an object is queued or running at a time (a partial unique index);
--   * the counts and the offset never go backwards or negative;
--   * a job names its own organization's object and keys (the guards), and its starter and mapping never change.

create table import_job (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name  text        not null,
    started_by       uuid        not null,
    membership_id    uuid        not null,
    operation        text        not null,
    external_id_field text,
    dry_run          boolean     not null default false,
    delimiter        text        not null default 'COMMA',
    mapping          jsonb       not null default '{}'::jsonb,
    state            text        not null default 'AWAITING_FILE',
    source_key       text,
    source_bytes     bigint      not null default 0,
    error_key        text,
    total_rows       integer     not null default 0,
    processed_rows   integer     not null default 0,
    created_rows     integer     not null default 0,
    updated_rows     integer     not null default 0,
    failed_rows      integer     not null default 0,
    cursor_offset    bigint      not null default 0,
    checked          boolean     not null default false,
    failure          text,
    cancel_requested boolean     not null default false,
    lease_until      timestamptz,
    finished_at      timestamptz,
    expires_at       timestamptz,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint import_job_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint import_job_operation_known check (operation in ('CREATE', 'UPSERT')),
    constraint import_job_state_known check (state in ('AWAITING_FILE', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED',
        'CANCELLED')),
    constraint import_job_delimiter_known check (delimiter in ('COMMA', 'SEMICOLON', 'TAB')),
    constraint import_job_upsert_has_field check (operation <> 'UPSERT' or external_id_field is not null),
    constraint import_job_counts_positive check (total_rows >= 0 and processed_rows >= 0 and created_rows >= 0
        and updated_rows >= 0 and failed_rows >= 0 and cursor_offset >= 0 and source_bytes >= 0),
    constraint import_job_failure_length check (failure is null or char_length(failure) <= 2000),
    constraint import_job_mapping_size check (pg_column_size(mapping) <= 65536)
);

-- At most one import of an object is waiting or running.
create unique index import_job_active_object on import_job (tenant_id, object_api_name)
    where state in ('QUEUED', 'RUNNING') and deleted_at is null;
create index import_job_tenant_created on import_job (tenant_id, created_at desc) where deleted_at is null;
create index import_job_tenant_state on import_job (tenant_id, state) where deleted_at is null;

create function platform_import_job_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        if new.object_api_name is distinct from old.object_api_name or new.started_by is distinct from old.started_by
                or new.membership_id is distinct from old.membership_id or new.operation is distinct from old.operation
                or new.mapping is distinct from old.mapping or new.dry_run is distinct from old.dry_run
                or new.external_id_field is distinct from old.external_id_field then
            raise exception 'import guard: what an import does and for whom never changes'
                using errcode = 'check_violation';
        end if;
        if new.processed_rows < old.processed_rows or new.cursor_offset < old.cursor_offset
                or new.created_rows < old.created_rows or new.updated_rows < old.updated_rows
                or new.failed_rows < old.failed_rows then
            raise exception 'import guard: the progress of an import never goes backwards'
                using errcode = 'check_violation';
        end if;
        if old.state in ('COMPLETED', 'FAILED', 'CANCELLED') and new.state is distinct from old.state then
            raise exception 'import guard: an import that has ended does not start again'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_import_job_guard() is
    'An import keeps its object, its starter, its operation and its mapping, never goes backwards and does not restart once ended (ADR-0123).';

create trigger import_job_row_guard before insert or update on import_job
    for each row execute function platform_row_guard();
create trigger import_job_tenant_guard before update on import_job
    for each row execute function platform_tenant_guard();
create trigger import_job_use_guard before insert or update on import_job
    for each row execute function platform_import_job_guard();

alter table import_job enable row level security;
alter table import_job force row level security;
create policy import_job_insert on import_job for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy import_job_select on import_job for select
    using (tenant_id = (select platform_current_tenant()));
create policy import_job_update on import_job for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy import_job_delete on import_job for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table import_job is
    'A CSV import: what to do, the mapping, the state, the counts and where it stands in the file. Tenant-scoped, row level security forced (ADR-0123).';

-- ---------------------------------------------------------------------------------------------------------------
-- import_error
-- ---------------------------------------------------------------------------------------------------------------

create table import_error (
    id         uuid        primary key default uuidv7(),
    tenant_id  uuid        not null default platform_current_tenant() references tenant (id),
    job_id     uuid        not null references import_job (id) on delete cascade,
    row_number integer     not null,
    reason     text        not null,
    raw_row    text        not null default '',
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint import_error_row_positive check (row_number >= 1),
    constraint import_error_reason_length check (char_length(reason) between 1 and 1000),
    constraint import_error_raw_length check (char_length(raw_row) <= 65536)
);

create unique index import_error_tenant_job_row on import_error (tenant_id, job_id, row_number)
    where deleted_at is null;

create trigger import_error_row_guard before insert or update on import_error
    for each row execute function platform_row_guard();
create trigger import_error_tenant_guard before update on import_error
    for each row execute function platform_tenant_guard();

alter table import_error enable row level security;
alter table import_error force row level security;
create policy import_error_insert on import_error for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy import_error_select on import_error for select
    using (tenant_id = (select platform_current_tenant()));
create policy import_error_update on import_error for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy import_error_delete on import_error for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table import_error is
    'A row of an import that failed: its number, the reason and the row as the file had it. Tenant-scoped, row level security forced (ADR-0123).';
