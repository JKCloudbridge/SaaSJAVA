-- The audience a step of an approval suggests for a template (Functional track FA6G, ADR-0200). Expand only; nothing is
-- removed or renamed.
--
--   inspection_audience_suggestion  One row for each location (and, optionally, department) the person who approved a
--                                   step that asks for a choice proposed. It is a suggestion and nothing more: no
--                                   share is made and nobody gains access to the template through it. The person who
--                                   publishes later is shown it ready-filled and may change it.
--
-- What must hold even if the application had a bug:
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * a row is written only by the approval of the step: the insert needs the transaction setting
--     platform.audience_suggestion_write = 'on', which only the step's approval raises;
--   * a row never changes and is never deleted or soft-deleted, so a suggestion stays what the approver proposed;
--   * a request holds a given location with a given department (or none) at most once.
-- The location and department are codes of the organization's own lists, so a row is never written to a log, an audit
-- record, an event or a notice; the audit says only that a suggestion was made and how many places it holds.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. The request is an identifier only: the approval tables belong to another module and are not referenced.

create table inspection_audience_suggestion (
    id             uuid        primary key default uuidv7(),
    tenant_id      uuid        not null default platform_current_tenant() references tenant (id),
    request_id     uuid        not null,
    template_id    uuid        not null,
    step_position  integer     not null,
    location       text        not null,
    department     text,
    version        bigint      not null default 0,
    created_at     timestamptz not null default now(),
    created_by     uuid        not null,
    updated_at     timestamptz not null default now(),
    updated_by     uuid        not null,
    deleted_at     timestamptz,
    deleted_by     uuid,
    constraint inspection_audience_suggestion_step_range check (step_position between 1 and 100),
    constraint inspection_audience_suggestion_codes_shape check (
        location ~ '^[A-Za-z0-9_-]{1,10}$' and (department is null or department ~ '^[A-Za-z0-9_-]{1,10}$'))
);

create unique index inspection_audience_suggestion_place
    on inspection_audience_suggestion (tenant_id, request_id, location, coalesce(department, ''));
create index inspection_audience_suggestion_tenant_request
    on inspection_audience_suggestion (tenant_id, request_id, created_at);

create function platform_inspection_audience_suggestion_guard() returns trigger
    language plpgsql
as $guard$
begin
    -- Messages name the table only, never a value.
    if tg_op = 'INSERT' then
        if coalesce(current_setting('platform.audience_suggestion_write', true), '') <> 'on' then
            raise exception 'suggestion guard: a suggestion of % is written only by the approval of its step',
                tg_table_name using errcode = 'check_violation';
        end if;
        return new;
    end if;
    raise exception 'suggestion guard: the suggestions of % are kept as they were made', tg_table_name
        using errcode = 'check_violation';
end;
$guard$;

create function platform_inspection_audience_suggestion_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'suggestion guard: the suggestions of % are kept', tg_table_name
        using errcode = 'check_violation';
end;
$guard$;

create trigger inspection_audience_suggestion_guard before insert or update on inspection_audience_suggestion
    for each row execute function platform_inspection_audience_suggestion_guard();
create trigger inspection_audience_suggestion_no_delete before delete on inspection_audience_suggestion
    for each row execute function platform_inspection_audience_suggestion_no_delete();
create trigger inspection_audience_suggestion_row_guard before insert or update on inspection_audience_suggestion
    for each row execute function platform_row_guard();
create trigger inspection_audience_suggestion_tenant_guard before update on inspection_audience_suggestion
    for each row execute function platform_tenant_guard();

alter table inspection_audience_suggestion enable row level security;
alter table inspection_audience_suggestion force row level security;
create policy inspection_audience_suggestion_insert on inspection_audience_suggestion for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_audience_suggestion_select on inspection_audience_suggestion for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_audience_suggestion_update on inspection_audience_suggestion for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_audience_suggestion_delete on inspection_audience_suggestion for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_audience_suggestion is
    'The location and departments a step of an approval suggested for a template. A suggestion only; never shared (ADR-0200).';
