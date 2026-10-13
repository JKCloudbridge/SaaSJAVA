-- The slots of the record table and the state of the index policy (Sprint 16, ADR-0100 to ADR-0104).
--
-- A field flagged "indexed" (V040) is served by a typed copy of its value in one physical column of the record table,
-- a "slot", because with forced row level security PostgreSQL does not use an index on the JSON of the record
-- (ADR-0083, condition 5). V036 reserved eleven slots; this migration widens them to twenty-two, adds the two
-- timestamp slots a date-and-time field needs (a date slot holds only the day and could not sort by time), creates the
-- static indexes on the slots and the table that says which field holds which slot.
--
--   field_slot   One row per assigned slot: which field of which object of which organization holds which slot, and
--                how far its copy is. State FILLING: the copy of the records that existed before the assignment is
--                being written (a record written meanwhile carries its own copy); FILLED: every record has its copy
--                and the field counts as indexed for queries; CLEARING: the field is no longer indexed, the copies
--                are being removed, and the slot is free again only when that is done (a slot is never reused with
--                the values of another field in it). Changing which slots are assigned is done under an exclusive
--                advisory lock of the object that every write holds in shared mode (ADR-0101): a write that did not
--                see an assignment has ended before the assignment becomes visible, so no record can miss its copy.
--
-- The indexes are made once, here: tenant first, then the object, then the slot, then the identifier, on live rows
-- that hold a value. A slot nobody uses holds no value, so it has no index entries and costs no write. They keep the
-- shape the benchmark measured (benchmark/sql/02-indexes.sql) except that rows without a value are left out: the
-- query engine finds them with the list index (ADR-0105).
--
-- What must hold even if the application had a bug:
--   * a slot holds the copy of one field only: one row per slot and one per field, per organization and object;
--   * a slot, its field and its object never change after the assignment (the guard below);
--   * a tenant sees and changes only its own rows (forced row level security).

-- ---------------------------------------------------------------------------------------------------------------
-- more slots
-- ---------------------------------------------------------------------------------------------------------------

alter table object_record
    add column s_text_5 text,
    add column s_text_6 text,
    add column s_text_7 text,
    add column s_text_8 text,
    add column s_num_3  double precision,
    add column s_num_4  double precision,
    add column s_ts_1   timestamptz,
    add column s_ts_2   timestamptz,
    add column s_ref_3  uuid,
    add column s_ref_4  uuid,
    add column s_bool_2 boolean;

-- ---------------------------------------------------------------------------------------------------------------
-- static indexes: one per slot (a slot without values has no entries), and one on the creation time
-- ---------------------------------------------------------------------------------------------------------------

create index object_record_s_text_1 on object_record (tenant_id, object_api_name, s_text_1, id)
    where s_text_1 is not null and deleted_at is null;
create index object_record_s_text_2 on object_record (tenant_id, object_api_name, s_text_2, id)
    where s_text_2 is not null and deleted_at is null;
create index object_record_s_text_3 on object_record (tenant_id, object_api_name, s_text_3, id)
    where s_text_3 is not null and deleted_at is null;
create index object_record_s_text_4 on object_record (tenant_id, object_api_name, s_text_4, id)
    where s_text_4 is not null and deleted_at is null;
create index object_record_s_text_5 on object_record (tenant_id, object_api_name, s_text_5, id)
    where s_text_5 is not null and deleted_at is null;
create index object_record_s_text_6 on object_record (tenant_id, object_api_name, s_text_6, id)
    where s_text_6 is not null and deleted_at is null;
create index object_record_s_text_7 on object_record (tenant_id, object_api_name, s_text_7, id)
    where s_text_7 is not null and deleted_at is null;
create index object_record_s_text_8 on object_record (tenant_id, object_api_name, s_text_8, id)
    where s_text_8 is not null and deleted_at is null;
create index object_record_s_num_1 on object_record (tenant_id, object_api_name, s_num_1, id)
    where s_num_1 is not null and deleted_at is null;
create index object_record_s_num_2 on object_record (tenant_id, object_api_name, s_num_2, id)
    where s_num_2 is not null and deleted_at is null;
create index object_record_s_num_3 on object_record (tenant_id, object_api_name, s_num_3, id)
    where s_num_3 is not null and deleted_at is null;
create index object_record_s_num_4 on object_record (tenant_id, object_api_name, s_num_4, id)
    where s_num_4 is not null and deleted_at is null;
create index object_record_s_date_1 on object_record (tenant_id, object_api_name, s_date_1, id)
    where s_date_1 is not null and deleted_at is null;
create index object_record_s_date_2 on object_record (tenant_id, object_api_name, s_date_2, id)
    where s_date_2 is not null and deleted_at is null;
create index object_record_s_ts_1 on object_record (tenant_id, object_api_name, s_ts_1, id)
    where s_ts_1 is not null and deleted_at is null;
create index object_record_s_ts_2 on object_record (tenant_id, object_api_name, s_ts_2, id)
    where s_ts_2 is not null and deleted_at is null;
create index object_record_s_ref_1 on object_record (tenant_id, object_api_name, s_ref_1, id)
    where s_ref_1 is not null and deleted_at is null;
create index object_record_s_ref_2 on object_record (tenant_id, object_api_name, s_ref_2, id)
    where s_ref_2 is not null and deleted_at is null;
create index object_record_s_ref_3 on object_record (tenant_id, object_api_name, s_ref_3, id)
    where s_ref_3 is not null and deleted_at is null;
create index object_record_s_ref_4 on object_record (tenant_id, object_api_name, s_ref_4, id)
    where s_ref_4 is not null and deleted_at is null;
create index object_record_s_bool_1 on object_record (tenant_id, object_api_name, s_bool_1, id)
    where s_bool_1 is not null and deleted_at is null;
create index object_record_s_bool_2 on object_record (tenant_id, object_api_name, s_bool_2, id)
    where s_bool_2 is not null and deleted_at is null;

-- "Newest first" is the most common list there is. The creation time never changes, so this index costs an entry per
-- insert and nothing per update. The time of the last change is deliberately not indexed: it changes on every update
-- and would make every update write an index entry (ADR-0105).
create index object_record_created on object_record (tenant_id, object_api_name, created_at, id)
    where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- field_slot
-- ---------------------------------------------------------------------------------------------------------------

create table field_slot (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    field_api_name  text        not null,
    slot            text        not null,
    state           text        not null default 'FILLING',
    cursor_id       uuid,
    records_done    bigint      not null default 0,
    verified_at     timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint field_slot_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint field_slot_field_name_shape check (field_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint field_slot_slot_known check (slot in (
        's_text_1', 's_text_2', 's_text_3', 's_text_4', 's_text_5', 's_text_6', 's_text_7', 's_text_8',
        's_num_1', 's_num_2', 's_num_3', 's_num_4', 's_date_1', 's_date_2', 's_ts_1', 's_ts_2',
        's_ref_1', 's_ref_2', 's_ref_3', 's_ref_4', 's_bool_1', 's_bool_2')),
    constraint field_slot_state_known check (state in ('FILLING', 'FILLED', 'CLEARING')),
    constraint field_slot_progress_positive check (records_done >= 0)
);

-- Never two fields on one slot, never two slots for one field (a slot that is being cleared still counts: it is free
-- only when its row is gone).
create unique index field_slot_tenant_slot on field_slot (tenant_id, object_api_name, slot)
    where deleted_at is null;
create unique index field_slot_tenant_field on field_slot (tenant_id, object_api_name, field_api_name)
    where deleted_at is null;

-- A slot stays with its field and its object, and a filled copy does not go back to being filled in.
create function platform_field_slot_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.object_api_name is distinct from old.object_api_name
            or new.field_api_name is distinct from old.field_api_name
            or new.slot is distinct from old.slot then
        raise exception 'slot guard: the field, object and slot of a row in % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    if old.state = 'CLEARING' and new.state <> 'CLEARING' then
        raise exception 'slot guard: a slot that is being cleared stays so until it is freed' using errcode = 'check_violation';
    end if;
    if old.state = 'FILLED' and new.state = 'FILLING' then
        raise exception 'slot guard: a complete copy is not filled in again' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_field_slot_guard() is
    'Refuses any update that moves a slot to another field or runs its states backwards (ADR-0101).';

create trigger field_slot_row_guard before insert or update on field_slot
    for each row execute function platform_row_guard();
create trigger field_slot_tenant_guard before update on field_slot
    for each row execute function platform_tenant_guard();
create trigger field_slot_state_guard before update on field_slot
    for each row execute function platform_field_slot_guard();

alter table field_slot enable row level security;
alter table field_slot force row level security;
create policy field_slot_insert on field_slot for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy field_slot_select on field_slot for select
    using (tenant_id = (select platform_current_tenant()));
create policy field_slot_update on field_slot for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy field_slot_delete on field_slot for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table field_slot is
    'Which field of which object holds which slot of the record table, and how far its copy is. Tenant-scoped, row level security forced (ADR-0101).';

-- ---------------------------------------------------------------------------------------------------------------
-- maintenance of the copies does not count as a change of the record
-- ---------------------------------------------------------------------------------------------------------------

-- Filling and clearing a slot rewrites the copy of a value that did not change. If that counted as an update it would
-- raise the version of every record of the object (every form somebody has open would meet a conflict) and set "last
-- changed" to now. An update that changes slot columns and nothing else is therefore the one update that keeps the
-- version. The helper below says "nothing else changed"; a migration that adds a column to object_record must add it
-- to the helper (a test fails when a column is missing).
create function platform_record_only_slots_changed(old_row object_record, new_row object_record) returns boolean
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
        and old_row.payload = new_row.payload
$$;

comment on function platform_record_only_slots_changed(object_record, object_record) is
    'True when two versions of a record differ in slot columns only: the maintenance of the typed copies (ADR-0102).';

create or replace function platform_row_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        -- The database clock and the first version are not the caller's to choose.
        new.created_at := now();
        new.updated_at := new.created_at;
        new.version := 0;
        new.deleted_at := null;
        new.deleted_by := null;
        return new;
    end if;

    -- tg_op = 'UPDATE'. Messages name the table only, never a value.
    -- The one update that keeps the version: the maintenance of the typed copies of a record (ADR-0102). Checked only
    -- for the record table and only when the version is unchanged, so an ordinary update pays one comparison. The
    -- conditions are nested on purpose: PL/pgSQL resolves every function in an expression when it plans it, and the
    -- helper takes the record type, so it must be planned only when the trigger is on that table.
    if tg_table_name = 'object_record' then
        if new.version is not distinct from old.version and platform_record_only_slots_changed(old, new) then
            return new;
        end if;
    end if;
    if new.id is distinct from old.id then
        raise exception 'row guard: id of % is immutable', tg_table_name using errcode = 'check_violation';
    end if;
    if new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
        raise exception 'row guard: created_at and created_by of % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.version is distinct from old.version + 1 then
        raise exception 'row guard: an update of % must increase version by exactly one', tg_table_name
            using errcode = 'check_violation';
    end if;
    if old.deleted_at is not null and new.deleted_at is not null then
        raise exception 'row guard: a deleted row of % is read-only until it is restored', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        if new.deleted_by is null then
            raise exception 'row guard: deleting a row of % requires deleted_by', tg_table_name
                using errcode = 'check_violation';
        end if;
        new.deleted_at := now();
    else
        new.deleted_by := null;
    end if;
    new.updated_at := now();
    return new;
end;
$guard$;

comment on function platform_row_guard() is
    'Enforces the base schema conventions (ADR-0010): database-owned timestamps, immutable id and creation data, version +1 on every update, soft delete bookkeeping. The one exception: a change of the typed copies of a record alone keeps the version (ADR-0102).';
