-- The counter of auto-number fields (Sprint 15, ADR-0091, ADR-0096).
--
-- An auto-number field gives every new record the next number of a running count ("INV-000123"). The count belongs to
-- one field of one object in one organization, so a counter is one row per organization, object and field. The number
-- is taken in the transaction that creates the record, as the last step before the record is stored: the row is locked
-- for the shortest possible time, two instances creating records at once get two different numbers, a number is never
-- given twice (not even after the record is deleted), and a create that is rolled back leaves a gap (a gap-free count
-- would make every create of the object wait for the one before it to commit).
--
--   auto_number_counter  next_value is the number the next create receives; the first row of a field starts at the
--                        field's "start at" setting. The row is never deleted, so a number cannot come back.
--
-- What must hold even if the application had a bug:
--   * a counter never goes backwards and never changes organization, object or field (the row guard of V001 and the
--     tenant guard of V002; the counter guard below);
--   * a tenant sees and changes only its own counters (forced row level security).

create table auto_number_counter (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    field_api_name  text        not null,
    next_value      bigint      not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint auto_number_counter_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint auto_number_counter_field_name_shape check (field_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint auto_number_counter_value_positive check (next_value >= 0)
);

-- One counter per organization, object and field; tenant first. This is also the index the upsert of the next number
-- uses, so taking a number is one index probe and one row update.
create unique index auto_number_counter_tenant_key
    on auto_number_counter (tenant_id, object_api_name, field_api_name) where deleted_at is null;

-- A counter only moves forward, and it stays with its field.
create function platform_auto_number_counter_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.next_value < old.next_value then
        raise exception 'counter guard: the counter in % never goes backwards', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.object_api_name is distinct from old.object_api_name
            or new.field_api_name is distinct from old.field_api_name then
        raise exception 'counter guard: the object and field of a counter in % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_auto_number_counter_guard() is
    'Refuses any update that moves an auto-number counter backwards or to another field (ADR-0096).';

create trigger auto_number_counter_row_guard before insert or update on auto_number_counter
    for each row execute function platform_row_guard();
create trigger auto_number_counter_tenant_guard before update on auto_number_counter
    for each row execute function platform_tenant_guard();
create trigger auto_number_counter_value_guard before update on auto_number_counter
    for each row execute function platform_auto_number_counter_guard();

alter table auto_number_counter enable row level security;
alter table auto_number_counter force row level security;
create policy auto_number_counter_insert on auto_number_counter for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy auto_number_counter_select on auto_number_counter for select
    using (tenant_id = (select platform_current_tenant()));
create policy auto_number_counter_update on auto_number_counter for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy auto_number_counter_delete on auto_number_counter for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table auto_number_counter is
    'The running count of one auto-number field of one object in one organization. Tenant-scoped, row level security forced (ADR-0096).';
