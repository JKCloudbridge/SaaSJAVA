-- Who points at whom (Sprint 16, ADR-0106).
--
-- A lookup or master-detail field holds the identifier of another record. To carry out what removing a record does to
-- the records that point at it (clear the pointer, refuse, remove them with it) the platform must find those records
-- without reading every record of every object, and the answer must not depend on which fields an administrator
-- flagged as indexed. This table is that index: one row per reference value of a record.
--
--   record_link   which record (source_object, source_record) points, through which field (field_api_name), at which
--                 record (target_object, target_record). Written in the transaction of the write that sets, changes or
--                 clears the value; removed when the record is removed for good. A record in the recycle bin keeps its
--                 rows (it comes back with its pointers, which are checked when it does).
--   link_index    per object and reference field: whether the rows above are complete for the records that existed
--                 before this table did. A field in state FILLING is being filled in by a bounded background pass;
--                 until it is FILLED the platform refuses to remove a record such a field could point at (it could
--                 not know who points at it). Writes keep the rows right from the first day, so the pass is only for
--                 older records.
--
-- object_record.deleted_with names the record whose removal took this one to the recycle bin (a detail removed with
-- its master), so that restoring the master brings back exactly those and not the ones a person removed on purpose.
--
-- What must hold even if the application had a bug:
--   * a pointer is of one record through one field: one row per record and field (partial unique index);
--   * a tenant sees and changes only its own rows (forced row level security); a pointer to a record of another
--     organization cannot be stored by the application (it is checked on write, ADR-0097) and is never counted.

alter table object_record add column deleted_with uuid;

create index object_record_deleted_with on object_record (tenant_id, deleted_with) where deleted_with is not null;

-- A change of the cascade marker is an ordinary change of a record; only the typed copies are exempt from the version
-- (V041). The helper lists every column of the record, so it must list the new one.
create or replace function platform_record_only_slots_changed(old_row object_record, new_row object_record)
    returns boolean
    language sql
    immutable
as $$
    select old_row.id is not distinct from new_row.id
        and old_row.tenant_id is not distinct from new_row.tenant_id
        and old_row.object_api_name is not distinct from new_row.object_api_name
        and old_row.record_type is not distinct from new_row.record_type
        and old_row.owner_id is not distinct from new_row.owner_id
        and old_row.version is not distinct from new_row.version
        and old_row.created_at is not distinct from new_row.created_at
        and old_row.created_by is not distinct from new_row.created_by
        and old_row.updated_at is not distinct from new_row.updated_at
        and old_row.updated_by is not distinct from new_row.updated_by
        and old_row.deleted_at is not distinct from new_row.deleted_at
        and old_row.deleted_by is not distinct from new_row.deleted_by
        and old_row.deleted_with is not distinct from new_row.deleted_with
        and old_row.payload = new_row.payload
$$;

-- ---------------------------------------------------------------------------------------------------------------
-- record_link
-- ---------------------------------------------------------------------------------------------------------------

create table record_link (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    source_object   text        not null,
    source_record   uuid        not null,
    field_api_name  text        not null,
    target_object   text        not null,
    target_record   uuid        not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint record_link_source_name_shape check (source_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_link_target_name_shape check (target_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_link_field_name_shape check (field_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$')
);

-- One pointer per record and field.
create unique index record_link_tenant_source on record_link (tenant_id, source_record, field_api_name)
    where deleted_at is null;

-- "Who points at this record": the question every removal asks.
create index record_link_tenant_target on record_link (tenant_id, target_record, source_object, field_api_name)
    where deleted_at is null;

-- A pointer keeps its source, its field and its object.
create function platform_record_link_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.source_object is distinct from old.source_object
            or new.source_record is distinct from old.source_record
            or new.field_api_name is distinct from old.field_api_name then
        raise exception 'link guard: the source of a pointer in % is immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_record_link_guard() is
    'Refuses any update that moves a pointer to another record or field (ADR-0106).';

create trigger record_link_row_guard before insert or update on record_link
    for each row execute function platform_row_guard();
create trigger record_link_tenant_guard before update on record_link
    for each row execute function platform_tenant_guard();
create trigger record_link_source_guard before update on record_link
    for each row execute function platform_record_link_guard();

alter table record_link enable row level security;
alter table record_link force row level security;
create policy record_link_insert on record_link for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy record_link_select on record_link for select
    using (tenant_id = (select platform_current_tenant()));
create policy record_link_update on record_link for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy record_link_delete on record_link for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table record_link is
    'Who points at whom: one row per reference value of a record. Tenant-scoped, row level security forced (ADR-0106).';

-- ---------------------------------------------------------------------------------------------------------------
-- link_index
-- ---------------------------------------------------------------------------------------------------------------

create table link_index (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    field_api_name  text        not null,
    state           text        not null default 'FILLING',
    cursor_id       uuid,
    records_done    bigint      not null default 0,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint link_index_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint link_index_field_name_shape check (field_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint link_index_state_known check (state in ('FILLING', 'FILLED')),
    constraint link_index_progress_positive check (records_done >= 0)
);

create unique index link_index_tenant_field on link_index (tenant_id, object_api_name, field_api_name)
    where deleted_at is null;

create trigger link_index_row_guard before insert or update on link_index
    for each row execute function platform_row_guard();
create trigger link_index_tenant_guard before update on link_index
    for each row execute function platform_tenant_guard();

alter table link_index enable row level security;
alter table link_index force row level security;
create policy link_index_insert on link_index for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy link_index_select on link_index for select
    using (tenant_id = (select platform_current_tenant()));
create policy link_index_update on link_index for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy link_index_delete on link_index for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table link_index is
    'Whether the pointers of a reference field are complete for the records that existed before record_link did. Tenant-scoped, row level security forced (ADR-0106).';

-- ---------------------------------------------------------------------------------------------------------------
-- a record removed for good takes its pointers with it
-- ---------------------------------------------------------------------------------------------------------------

-- Whoever removes a record for good (a person, the clean-up of the recycle bin) leaves no pointer behind, without any
-- of them having to remember: the database does it.
create function platform_record_link_cleanup() returns trigger
    language plpgsql
as $cleanup$
begin
    delete from record_link where tenant_id = old.tenant_id and source_record = old.id;
    return old;
end;
$cleanup$;

comment on function platform_record_link_cleanup() is
    'Removes the pointers of a record that is deleted for good (ADR-0106).';

create trigger object_record_link_cleanup after delete on object_record
    for each row execute function platform_record_link_cleanup();
