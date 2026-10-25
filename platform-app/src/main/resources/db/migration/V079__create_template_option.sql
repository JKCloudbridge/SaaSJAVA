-- The two sheet options of a template (Functional track FA7, ADR-0201). Expand only; nothing is removed or renamed.
--
--   inspection_template_option  One row for each template whose options were ever set: "view only" (nobody can start
--                               a fill-in from the template) and "allow download" (the sheet may be given as a file).
--                               A template with no row has both off, which is what every template made before this
--                               release means.
--
-- Why a table of its own and not two fields of the template record: the record is locked for everyone while a version
-- waits for approval, and Plant QMS publishes (and may change the options) exactly then. The options are not part of
-- what is approved, so they stay outside the locked record.
--
-- What must hold even if the application had a bug:
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * a row is written or changed only by the cluster's own acts (publish, the "Sheet options" act): both need the
--     transaction setting platform.template_option_write = 'on', which only the store raises, in the statement itself;
--   * a row never moves to another template or organization, and is never deleted or soft-deleted;
--   * a template has at most one live row.
-- Two yes/no values are the only content: no name, number or code is stored here, so nothing needs redacting.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger.

create table inspection_template_option (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    template_id     uuid        not null,
    view_only       boolean     not null default false,
    allow_download  boolean     not null default false,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid
);

create unique index inspection_template_option_template
    on inspection_template_option (tenant_id, template_id)
    where deleted_at is null;

create function platform_inspection_template_option_guard() returns trigger
    language plpgsql
as $guard$
begin
    -- Messages name the table only, never a value.
    if coalesce(current_setting('platform.template_option_write', true), '') <> 'on' then
        raise exception 'option guard: the options of % are written only by the acts of the template cluster',
            tg_table_name using errcode = 'check_violation';
    end if;
    if tg_op = 'UPDATE' then
        if new.template_id is distinct from old.template_id then
            raise exception 'option guard: the options of % never move to another template', tg_table_name
                using errcode = 'check_violation';
        end if;
        if new.deleted_at is distinct from old.deleted_at or new.deleted_by is distinct from old.deleted_by then
            raise exception 'option guard: the options of % are kept', tg_table_name
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create function platform_inspection_template_option_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'option guard: the options of % are kept', tg_table_name using errcode = 'check_violation';
end;
$guard$;

create trigger inspection_template_option_guard before insert or update on inspection_template_option
    for each row execute function platform_inspection_template_option_guard();
create trigger inspection_template_option_no_delete before delete on inspection_template_option
    for each row execute function platform_inspection_template_option_no_delete();
create trigger inspection_template_option_row_guard before insert or update on inspection_template_option
    for each row execute function platform_row_guard();
create trigger inspection_template_option_tenant_guard before update on inspection_template_option
    for each row execute function platform_tenant_guard();

alter table inspection_template_option enable row level security;
alter table inspection_template_option force row level security;
create policy inspection_template_option_insert on inspection_template_option for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_template_option_select on inspection_template_option for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_template_option_update on inspection_template_option for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_template_option_delete on inspection_template_option for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_template_option is
    'View only and allow download of a template, set when it is published and changeable later (ADR-0201).';
