-- The record store (Sprint 14, ADR-0084 to ADR-0090; the storage decision is gate G1, ADR-0082 and ADR-0083).
--
-- One table, tenant-scoped (ADR-0015): every record of every object that the generic store holds, that is the
-- organization's own objects and the standard objects that no platform module owns (Account, Contact, Opportunity,
-- Case). The metadata says what the JSON means (ADR-0058); this table only keeps it.
--
--   object_record  The record: which organization (tenant_id), which object (object_api_name, an API name from the
--                  metadata catalogue), the identifier (a version 7 UUID made by the database: opaque, time-ordered,
--                  never reveals a count), the values of the fields as one JSON object keyed by field API name
--                  (payload), the owner (a user; record-level security of Sprint 17 reads it), the record type (an API
--                  name, or null), the base columns and soft delete of ADR-0010.
--
-- The "slots" (s_text_1 ... s_bool_1) are typed COPIES of the values of fields that are flagged "indexed". They are
-- empty and unused until Sprint 16 (the query engine and the index policy fill them and index them). They are here
-- because the benchmark that decided the storage (ADR-0083) was run on exactly this shape and proved it: with row level
-- security forced, PostgreSQL does not use an index on the JSON field operator or on decimal comparisons (neither is
-- leakproof), so indexed fields must live in plain typed columns. Adding them later would be a legal expand
-- migration, but the proven table is the one to ship. The application never reads or writes them in Sprint 14.
--
-- What must hold even if the application had a bug:
--   * the organization and the object of a record never change, nor its identifier (the tenant guard of V002, the
--     identity guard below, the row guard of V001);
--   * every update raises the version by exactly one (the row guard), and a record in the recycle bin is read-only
--     until it is restored (the row guard);
--   * the payload is a JSON object of bounded size; the object name and the record type have the shape of an API name;
--   * a tenant sees and changes only its own rows (forced row level security).
--
-- A live row is deleted only by a soft delete (deleted_at); the permanent delete of a row in the recycle bin is a plain
-- DELETE (ADR-0010). Unique indexes would have to be partial; there are none besides the primary key.

create table object_record (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    record_type     text,
    owner_id        uuid        not null,
    payload         jsonb       not null default '{}'::jsonb,
    s_text_1        text,
    s_text_2        text,
    s_text_3        text,
    s_text_4        text,
    s_num_1         double precision,
    s_num_2         double precision,
    s_date_1        date,
    s_date_2        date,
    s_ref_1         uuid,
    s_ref_2         uuid,
    s_bool_1        boolean,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint object_record_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint object_record_record_type_shape
        check (record_type is null or record_type ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint object_record_payload_is_object check (jsonb_typeof(payload) = 'object'),
    constraint object_record_payload_size check (pg_column_size(payload) <= 524288)
) with (fillfactor = 90);

-- ---------------------------------------------------------------------------------------------------------------
-- indexes: tenant first, the object second, live rows and recycle-bin rows apart (what the benchmark proved)
-- ---------------------------------------------------------------------------------------------------------------

-- The list of an object's live records in identifier order (the minimal list of Sprint 14, and what every later query
-- starts from), and the existence check of the object usage.
create index object_record_list on object_record (tenant_id, object_api_name, id) where deleted_at is null;

-- The recycle bin of an object, in identifier order.
create index object_record_bin on object_record (tenant_id, object_api_name, id) where deleted_at is not null;

-- The retention clean-up: what has been in the recycle bin longer than the retention period, per organization.
create index object_record_bin_age on object_record (tenant_id, deleted_at) where deleted_at is not null;

-- ---------------------------------------------------------------------------------------------------------------
-- guards, triggers, row level security, comment
-- ---------------------------------------------------------------------------------------------------------------

-- A record never changes object: its fields, its permissions and its meaning all belong to the object. (The
-- organization and the identifier are guarded by the tenant guard and the row guard.)
create function platform_record_identity_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.object_api_name is distinct from old.object_api_name then
        raise exception 'record guard: the object of a record in % is immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_record_identity_guard() is
    'Refuses any update that changes the object of a record (ADR-0084).';

create trigger object_record_row_guard before insert or update on object_record
    for each row execute function platform_row_guard();
create trigger object_record_tenant_guard before update on object_record
    for each row execute function platform_tenant_guard();
create trigger object_record_identity_guard before update on object_record
    for each row execute function platform_record_identity_guard();

alter table object_record enable row level security;
alter table object_record force row level security;
create policy object_record_insert on object_record for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy object_record_select on object_record for select
    using (tenant_id = (select platform_current_tenant()));
create policy object_record_update on object_record for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy object_record_delete on object_record for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table object_record is
    'Records of the generic store: JSON values keyed by field API name, with owner, soft delete and version. Tenant-scoped, row level security forced (ADR-0084).';
