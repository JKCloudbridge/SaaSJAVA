-- Two small records of the Quality Management System home page (Functional track FA9, ADR-0203).
--
-- inspection_fill_department: the departments of the person who started a fill-in, as location and department codes,
-- written once in the transaction that makes the fill-in. A fill-in has no department of its own; this is what lets the
-- home page filter and group fill-ins by department honestly. A fill-in made before this table existed has no row and is
-- counted as "not recorded". One row for each pair (a person in two departments has two rows).
--
-- inspection_template_event: what happened to a template that people want to see in a timeline and count by month:
-- published, revised (a new revision published), taken back, retired. Written by the acts themselves, in their own
-- transaction. The history starts with this release: nothing is invented for what happened before.
--
-- Both are append-only: the guard refuses a write that does not come from the act that owns it and any change or delete
-- afterwards, for the application role and for the owner. Messages name the table only, never a value.
-- Expand only: two new tables and two partial indexes; nothing existing is touched.

create table inspection_fill_department (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    fill_id     uuid        not null,
    location    text        not null,
    department  text        not null,
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint inspection_fill_department_codes_shape check (
        location ~ '^[A-Za-z0-9_-]{1,10}$' and department ~ '^[A-Za-z0-9_-]{1,10}$')
);

create unique index inspection_fill_department_pair
    on inspection_fill_department (tenant_id, fill_id, location, department) where deleted_at is null;
create index inspection_fill_department_tenant_place
    on inspection_fill_department (tenant_id, location, department, fill_id) where deleted_at is null;

create table inspection_template_event (
    id           uuid        primary key default uuidv7(),
    tenant_id    uuid        not null default platform_current_tenant() references tenant (id),
    template_id  uuid        not null,
    kind         text        not null,
    version_no   integer     not null,
    revision_no  integer,
    version      bigint      not null default 0,
    created_at   timestamptz not null default now(),
    created_by   uuid        not null,
    updated_at   timestamptz not null default now(),
    updated_by   uuid        not null,
    deleted_at   timestamptz,
    deleted_by   uuid,
    constraint inspection_template_event_kind_known check (kind in ('PUBLISHED', 'REVISED', 'UNPUBLISHED', 'RETIRED')),
    constraint inspection_template_event_version_range check (version_no between 0 and 100000),
    constraint inspection_template_event_revision_range check (revision_no is null or revision_no between 0 and 99999)
);

create index inspection_template_event_tenant_time
    on inspection_template_event (tenant_id, created_at desc, id) where deleted_at is null;
create index inspection_template_event_tenant_template
    on inspection_template_event (tenant_id, template_id, created_at) where deleted_at is null;

-- Two indexes of the fill-in table for the home page: the fill-ins sent in a period, and those that wait for a review.
-- Without them a period would read every fill-in of the organization. Partial, so they hold only what they serve.
create index sheet_fill_tenant_submitted
    on sheet_fill (tenant_id, fill_object, submitted_at, fill_id)
    where submitted_at is not null and deleted_at is null;
create index sheet_fill_tenant_awaiting
    on sheet_fill (tenant_id, fill_object, submitted_at, fill_id)
    where status = 'SUBMITTED' and approval_request_id is not null and deleted_at is null;

create function platform_inspection_fill_department_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if coalesce(current_setting('platform.fill_department_write', true), '') <> 'on' then
            raise exception 'department guard: a department of % is written only when the fill-in is made',
                tg_table_name using errcode = 'check_violation';
        end if;
        return new;
    end if;
    raise exception 'department guard: the departments of % are kept as they were', tg_table_name
        using errcode = 'check_violation';
end;
$guard$;

create function platform_inspection_template_event_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if coalesce(current_setting('platform.template_event_write', true), '') <> 'on' then
            raise exception 'event guard: an event of % is written only by the act it records', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    raise exception 'event guard: the events of % are kept as they were', tg_table_name
        using errcode = 'check_violation';
end;
$guard$;

create function platform_inspection_home_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'home guard: the rows of % are kept', tg_table_name using errcode = 'check_violation';
end;
$guard$;

create trigger inspection_fill_department_guard before insert or update on inspection_fill_department
    for each row execute function platform_inspection_fill_department_guard();
create trigger inspection_fill_department_no_delete before delete on inspection_fill_department
    for each row execute function platform_inspection_home_no_delete();
create trigger inspection_fill_department_row_guard before insert or update on inspection_fill_department
    for each row execute function platform_row_guard();
create trigger inspection_fill_department_tenant_guard before update on inspection_fill_department
    for each row execute function platform_tenant_guard();

create trigger inspection_template_event_guard before insert or update on inspection_template_event
    for each row execute function platform_inspection_template_event_guard();
create trigger inspection_template_event_no_delete before delete on inspection_template_event
    for each row execute function platform_inspection_home_no_delete();
create trigger inspection_template_event_row_guard before insert or update on inspection_template_event
    for each row execute function platform_row_guard();
create trigger inspection_template_event_tenant_guard before update on inspection_template_event
    for each row execute function platform_tenant_guard();

alter table inspection_fill_department enable row level security;
alter table inspection_fill_department force row level security;
create policy inspection_fill_department_insert on inspection_fill_department for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_fill_department_select on inspection_fill_department for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_fill_department_update on inspection_fill_department for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_fill_department_delete on inspection_fill_department for delete
    using (tenant_id = (select platform_current_tenant()));

alter table inspection_template_event enable row level security;
alter table inspection_template_event force row level security;
create policy inspection_template_event_insert on inspection_template_event for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_template_event_select on inspection_template_event for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_template_event_update on inspection_template_event for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_template_event_delete on inspection_template_event for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_fill_department is
    'The departments (location and department codes) of the person who started a fill-in, written once with it (ADR-0203).';
comment on table inspection_template_event is
    'Published, revised, taken back and retired: what happened to a template, for the home page timeline and counts (ADR-0203).';
