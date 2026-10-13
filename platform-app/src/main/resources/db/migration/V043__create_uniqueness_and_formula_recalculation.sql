-- Uniqueness of field values and the recalculation of stored formula results (Sprint 16A, ADR-0111 to ADR-0114).
--
-- UNIQUENESS. A field flagged unique is served by its typed copy in a slot (V041) and the database itself refuses a
-- second live record with the same value. An index cannot be made per organization and object at run time (that would
-- be data definition language driven by an organization's metadata), so the indexes are made once, here: one partial
-- unique index per slot column, and six for the pairs of link slots of a junction. A record takes part in an index only
-- when its own bit of unique_mask is set. The application sets the bit in the same statement that writes the value, for
-- exactly the fields that are unique; every other record is outside the index and costs it nothing. A record in the
-- recycle bin is outside every index (deleted_at), so it never blocks a new value, and restoring it re-enters the
-- index: a restore that would duplicate a value is refused by the database.
--
--   unique_mask         bit n set: the record's value in constraint n must be unique among live records of its
--                       object. Bits 0 to 21 are the slot columns in the order of V041 (s_text_1 is bit 0, s_bool_2 is
--                       bit 21); bits 22 to 27 are the pairs of link slots (1+2, 1+3, 1+4, 2+3, 2+4, 3+4), which is how
--                       a junction keeps its two parents unique as a pair. Maintenance of the mask alone does not count
--                       as a change of the record (the helper of V041 does not name the column, on purpose).
--   unique_enforcement  one row per enforced constraint of an organization: which field (or pair of links) of which
--                       object, which bit, and how far the mask has been written for the records that existed before:
--                       ENFORCING (the mask is being written; writes already carry it and check existing values
--                       first), CONFLICT (two existing records hold the same value, so the mask cannot be completed;
--                       the field says how many, and the work resumes once the data is fixed) and ENFORCED (every live
--                       record carries its bit). Changed under the same exclusive object lock as field_slot (ADR-0101).
--
-- RECALCULATION. Editing a formula leaves the stored results of existing records as they were. The recalculation brings
-- them up to date in the background, per object, in bounded batches, resumably.
--
--   formula_recalculation  one row per organization and object: the fingerprint of the object's formulas that the
--                          stored results are known to be current for (current_stamp), the fingerprint a pass in
--                          flight is working towards (target_stamp), where it is, what it counted, and what the last
--                          finished pass counted. A query on a formula field is answered only while current_stamp is
--                          the fingerprint of the formulas now (ADR-0113).
--   The recalculation rewrites a formula result in the payload without raising the version or the time of last change:
--   it is not a change a person made, and it must not break the form somebody has open. The guard allows exactly that
--   one thing, and only inside a transaction that says so (a transaction-local setting); see the end of this file.
--
-- What must hold even if the application had a bug:
--   * two live records of one object never hold the same value in a constraint whose bit they carry;
--   * a constraint row stays with its object, kind, field and bit, and a complete one does not go back (guards below);
--   * a tenant sees and changes only its own rows (forced row level security);
--   * without the transaction-local setting a change of the payload needs the next version, as always.

-- ---------------------------------------------------------------------------------------------------------------
-- the mask and the unique indexes
-- ---------------------------------------------------------------------------------------------------------------

alter table object_record add column unique_mask integer not null default 0;

alter table object_record add constraint object_record_unique_mask_range
    check (unique_mask >= 0 and unique_mask < 268435456);

create unique index object_record_u_s_text_1 on object_record (tenant_id, object_api_name, s_text_1)
    where s_text_1 is not null and deleted_at is null and (unique_mask & 1) <> 0;
create unique index object_record_u_s_text_2 on object_record (tenant_id, object_api_name, s_text_2)
    where s_text_2 is not null and deleted_at is null and (unique_mask & 2) <> 0;
create unique index object_record_u_s_text_3 on object_record (tenant_id, object_api_name, s_text_3)
    where s_text_3 is not null and deleted_at is null and (unique_mask & 4) <> 0;
create unique index object_record_u_s_text_4 on object_record (tenant_id, object_api_name, s_text_4)
    where s_text_4 is not null and deleted_at is null and (unique_mask & 8) <> 0;
create unique index object_record_u_s_text_5 on object_record (tenant_id, object_api_name, s_text_5)
    where s_text_5 is not null and deleted_at is null and (unique_mask & 16) <> 0;
create unique index object_record_u_s_text_6 on object_record (tenant_id, object_api_name, s_text_6)
    where s_text_6 is not null and deleted_at is null and (unique_mask & 32) <> 0;
create unique index object_record_u_s_text_7 on object_record (tenant_id, object_api_name, s_text_7)
    where s_text_7 is not null and deleted_at is null and (unique_mask & 64) <> 0;
create unique index object_record_u_s_text_8 on object_record (tenant_id, object_api_name, s_text_8)
    where s_text_8 is not null and deleted_at is null and (unique_mask & 128) <> 0;
create unique index object_record_u_s_num_1 on object_record (tenant_id, object_api_name, s_num_1)
    where s_num_1 is not null and deleted_at is null and (unique_mask & 256) <> 0;
create unique index object_record_u_s_num_2 on object_record (tenant_id, object_api_name, s_num_2)
    where s_num_2 is not null and deleted_at is null and (unique_mask & 512) <> 0;
create unique index object_record_u_s_num_3 on object_record (tenant_id, object_api_name, s_num_3)
    where s_num_3 is not null and deleted_at is null and (unique_mask & 1024) <> 0;
create unique index object_record_u_s_num_4 on object_record (tenant_id, object_api_name, s_num_4)
    where s_num_4 is not null and deleted_at is null and (unique_mask & 2048) <> 0;
create unique index object_record_u_s_date_1 on object_record (tenant_id, object_api_name, s_date_1)
    where s_date_1 is not null and deleted_at is null and (unique_mask & 4096) <> 0;
create unique index object_record_u_s_date_2 on object_record (tenant_id, object_api_name, s_date_2)
    where s_date_2 is not null and deleted_at is null and (unique_mask & 8192) <> 0;
create unique index object_record_u_s_ts_1 on object_record (tenant_id, object_api_name, s_ts_1)
    where s_ts_1 is not null and deleted_at is null and (unique_mask & 16384) <> 0;
create unique index object_record_u_s_ts_2 on object_record (tenant_id, object_api_name, s_ts_2)
    where s_ts_2 is not null and deleted_at is null and (unique_mask & 32768) <> 0;
create unique index object_record_u_s_ref_1 on object_record (tenant_id, object_api_name, s_ref_1)
    where s_ref_1 is not null and deleted_at is null and (unique_mask & 65536) <> 0;
create unique index object_record_u_s_ref_2 on object_record (tenant_id, object_api_name, s_ref_2)
    where s_ref_2 is not null and deleted_at is null and (unique_mask & 131072) <> 0;
create unique index object_record_u_s_ref_3 on object_record (tenant_id, object_api_name, s_ref_3)
    where s_ref_3 is not null and deleted_at is null and (unique_mask & 262144) <> 0;
create unique index object_record_u_s_ref_4 on object_record (tenant_id, object_api_name, s_ref_4)
    where s_ref_4 is not null and deleted_at is null and (unique_mask & 524288) <> 0;
create unique index object_record_u_s_bool_1 on object_record (tenant_id, object_api_name, s_bool_1)
    where s_bool_1 is not null and deleted_at is null and (unique_mask & 1048576) <> 0;
create unique index object_record_u_s_bool_2 on object_record (tenant_id, object_api_name, s_bool_2)
    where s_bool_2 is not null and deleted_at is null and (unique_mask & 2097152) <> 0;

create unique index object_record_u_p_ref_1_ref_2 on object_record (tenant_id, object_api_name, s_ref_1, s_ref_2)
    where s_ref_1 is not null and s_ref_2 is not null and deleted_at is null and (unique_mask & 4194304) <> 0;
create unique index object_record_u_p_ref_1_ref_3 on object_record (tenant_id, object_api_name, s_ref_1, s_ref_3)
    where s_ref_1 is not null and s_ref_3 is not null and deleted_at is null and (unique_mask & 8388608) <> 0;
create unique index object_record_u_p_ref_1_ref_4 on object_record (tenant_id, object_api_name, s_ref_1, s_ref_4)
    where s_ref_1 is not null and s_ref_4 is not null and deleted_at is null and (unique_mask & 16777216) <> 0;
create unique index object_record_u_p_ref_2_ref_3 on object_record (tenant_id, object_api_name, s_ref_2, s_ref_3)
    where s_ref_2 is not null and s_ref_3 is not null and deleted_at is null and (unique_mask & 33554432) <> 0;
create unique index object_record_u_p_ref_2_ref_4 on object_record (tenant_id, object_api_name, s_ref_2, s_ref_4)
    where s_ref_2 is not null and s_ref_4 is not null and deleted_at is null and (unique_mask & 67108864) <> 0;
create unique index object_record_u_p_ref_3_ref_4 on object_record (tenant_id, object_api_name, s_ref_3, s_ref_4)
    where s_ref_3 is not null and s_ref_4 is not null and deleted_at is null and (unique_mask & 134217728) <> 0;

-- ---------------------------------------------------------------------------------------------------------------
-- unique_enforcement
-- ---------------------------------------------------------------------------------------------------------------

create table unique_enforcement (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name  text        not null,
    kind             text        not null,
    field_api_name   text        not null,
    partner_api_name text,
    constraint_bit   smallint    not null,
    state            text        not null default 'ENFORCING',
    cursor_id        uuid,
    records_done     bigint      not null default 0,
    conflicts        bigint      not null default 0,
    verified_at      timestamptz,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint unique_enforcement_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint unique_enforcement_field_name_shape check (field_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint unique_enforcement_partner_name_shape
        check (partner_api_name is null or partner_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint unique_enforcement_kind_known check (kind in ('VALUE', 'PAIR')),
    constraint unique_enforcement_pair_has_partner check ((kind = 'PAIR') = (partner_api_name is not null)),
    constraint unique_enforcement_constraint_bit_known check (constraint_bit between 0 and 27),
    constraint unique_enforcement_state_known check (state in ('ENFORCING', 'CONFLICT', 'ENFORCED')),
    constraint unique_enforcement_progress_positive check (records_done >= 0 and conflicts >= 0)
);

-- One bit of an object is enforced for one thing at a time, and one thing is enforced once.
create unique index unique_enforcement_tenant_bit on unique_enforcement (tenant_id, object_api_name, constraint_bit)
    where deleted_at is null;
create unique index unique_enforcement_tenant_field on unique_enforcement (tenant_id, object_api_name, kind,
    field_api_name) where deleted_at is null;

create function platform_unique_enforcement_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.object_api_name is distinct from old.object_api_name
            or new.kind is distinct from old.kind
            or new.field_api_name is distinct from old.field_api_name
            or new.partner_api_name is distinct from old.partner_api_name
            or new.constraint_bit is distinct from old.constraint_bit then
        raise exception 'unique guard: the object, kind, fields and bit of a row in % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    if old.state = 'ENFORCED' and new.state <> 'ENFORCED' then
        raise exception 'unique guard: a complete constraint does not go back' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_unique_enforcement_guard() is
    'Refuses any update that moves a constraint to another field or bit or runs its states backwards (ADR-0111).';

create trigger unique_enforcement_row_guard before insert or update on unique_enforcement
    for each row execute function platform_row_guard();
create trigger unique_enforcement_tenant_guard before update on unique_enforcement
    for each row execute function platform_tenant_guard();
create trigger unique_enforcement_state_guard before update on unique_enforcement
    for each row execute function platform_unique_enforcement_guard();

alter table unique_enforcement enable row level security;
alter table unique_enforcement force row level security;
create policy unique_enforcement_insert on unique_enforcement for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy unique_enforcement_select on unique_enforcement for select
    using (tenant_id = (select platform_current_tenant()));
create policy unique_enforcement_update on unique_enforcement for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy unique_enforcement_delete on unique_enforcement for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table unique_enforcement is
    'Which unique field (or junction pair) of which object is enforced by the database, and how far the mask of the existing records is. Tenant-scoped, row level security forced (ADR-0111).';

-- ---------------------------------------------------------------------------------------------------------------
-- formula_recalculation
-- ---------------------------------------------------------------------------------------------------------------

create table formula_recalculation (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name     text        not null,
    state               text        not null default 'IDLE',
    current_stamp       text,
    target_stamp        text,
    started_by          text,
    cursor_id           uuid,
    sweep_started_at    timestamptz,
    closing_after       timestamptz,
    started_at          timestamptz,
    examined            bigint      not null default 0,
    changed             bigint      not null default 0,
    unavailable         bigint      not null default 0,
    last_finished_at    timestamptz,
    last_examined       bigint      not null default 0,
    last_changed        bigint      not null default 0,
    last_unavailable    bigint      not null default 0,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint formula_recalculation_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint formula_recalculation_state_known check (state in ('IDLE', 'PENDING', 'RUNNING', 'CLOSING')),
    constraint formula_recalculation_started_by_known
        check (started_by is null or started_by in ('PUBLISH', 'MANUAL', 'RESUME')),
    constraint formula_recalculation_stamp_shape
        check ((current_stamp is null or char_length(current_stamp) <= 64)
            and (target_stamp is null or char_length(target_stamp) <= 64)),
    constraint formula_recalculation_counts_positive
        check (examined >= 0 and changed >= 0 and unavailable >= 0 and last_examined >= 0 and last_changed >= 0
            and last_unavailable >= 0)
);

-- One recalculation state per object: a second pass cannot start while one is in flight.
create unique index formula_recalculation_tenant_object on formula_recalculation (tenant_id, object_api_name)
    where deleted_at is null;

create trigger formula_recalculation_row_guard before insert or update on formula_recalculation
    for each row execute function platform_row_guard();
create trigger formula_recalculation_tenant_guard before update on formula_recalculation
    for each row execute function platform_tenant_guard();

alter table formula_recalculation enable row level security;
alter table formula_recalculation force row level security;
create policy formula_recalculation_insert on formula_recalculation for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy formula_recalculation_select on formula_recalculation for select
    using (tenant_id = (select platform_current_tenant()));
create policy formula_recalculation_update on formula_recalculation for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy formula_recalculation_delete on formula_recalculation for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table formula_recalculation is
    'Whether the stored formula results of an object are current for its formulas, and the pass that brings them there. Tenant-scoped, row level security forced (ADR-0113).';

-- ---------------------------------------------------------------------------------------------------------------
-- the recalculation does not count as a change of the record
-- ---------------------------------------------------------------------------------------------------------------

-- The recalculation rewrites the stored result of a formula in records nobody changed. Counted as an update it would
-- raise the version of every record of the object (every open form would meet a conflict) and set "last changed" to
-- now, with the name of nobody. It is therefore the second update that keeps the version, and only when the
-- transaction says it is the recalculation (set_config('platform.record_recalculation', 'on', true), transaction
-- local, switched off again by the caller). The helper says "nothing but the payload and the typed copies changed" for
-- a record that is live. A migration that adds a column to object_record must add it here too (a test fails otherwise).
create function platform_record_only_results_changed(old_row object_record, new_row object_record) returns boolean
    language sql
    immutable
as $$
    select old_row.deleted_at is null
        and old_row.id is not distinct from new_row.id
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
$$;

comment on function platform_record_only_results_changed(object_record, object_record) is
    'True when two versions of a live record differ in the payload and the typed copies only: the recalculation of stored formula results (ADR-0113).';

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
    -- The updates that keep the version, for the record table only and only when the version is unchanged, so an
    -- ordinary update pays one comparison: the maintenance of the typed copies (ADR-0102) and, inside a transaction
    -- that says so, the recalculation of stored formula results (ADR-0113). The conditions are nested on purpose:
    -- PL/pgSQL resolves every function in an expression when it plans it, and the helpers take the record type, so
    -- they must be planned only when the trigger is on that table.
    if tg_table_name = 'object_record' then
        if new.version is not distinct from old.version then
            if platform_record_only_slots_changed(old, new) then
                return new;
            end if;
            if current_setting('platform.record_recalculation', true) = 'on'
                    and platform_record_only_results_changed(old, new) then
                return new;
            end if;
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
    'Enforces the base schema conventions (ADR-0010): database-owned timestamps, immutable id and creation data, version +1 on every update, soft delete bookkeeping. The exceptions that keep the version: a change of the typed copies alone (ADR-0102) and, inside a transaction that says so, the recalculation of stored formula results (ADR-0113).';
