-- The limit of the score, the Action plan document of a form, and the notice of a low score (Functional track,
-- Management Core part 2B, ADR-0261). Expand only: two tables are added and a list the database checks is widened;
-- nothing is removed or renamed.
--
--   application_setting          One row for each setting an organization changed for an application in Setup,
--                                Application settings (today: the limit of the score). The solution file of the
--                                application declares which settings exist and their default; this table holds only
--                                what an organization chose, so an application with no row works on its defaults.
--   inspection_action_plan_link  One row for each document that says which other document is its Action plan form:
--                                the form a plant head fills when a fill-in of the document scores below the limit.
--                                Set by the creator when the document is published to the plants; kept apart from the
--                                document's own record because that record is locked while an approval runs.
--   kind ACTION_PLAN_NEEDED      The notice a plant head gets when a fill-in scored below the limit. It stores facts
--                                only (which event and where the fill-in's page lives): never the score and never the
--                                limit; the words are built when it is read.
--
-- What must hold even if the application had a bug:
--   * both tables are tenant-scoped with forced row level security and have no system scope;
--   * a setting is one per application and key: one live row for each, so two values for one setting cannot exist;
--   * the application and the key are lower case words and the value is a whole number in a bounded range, so nothing
--     typed freely is stored beyond them (the kind of the setting, a percent from 1 to 100, is checked by the
--     application against the file that declares it);
--   * a document has at most one live Action plan form and is never its own Action plan form;
--   * what a row says about its application, key and document never changes; a row is removed by marking it (soft
--     delete) and a removed row is not changed.
-- A value and a document chosen as the Action plan form are the organization's data: they never reach a log, an audit
-- record or an event (the audit names the application's key and the kind of change only). Neither table feeds an
-- answer of the permission module or the metadata, so neither needs a security-version or metadata-version trigger.

create table application_setting (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    application text        not null,
    setting_key text        not null,
    value       integer     not null,
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint application_setting_application_shape check (application ~ '^[a-z][a-z0-9-]{0,59}$'),
    constraint application_setting_key_shape check (setting_key ~ '^[a-z][A-Za-z0-9]{0,29}$'),
    constraint application_setting_value_range check (value between 0 and 1000000)
);

create unique index application_setting_one_per_key
    on application_setting (tenant_id, application, setting_key) where deleted_at is null;

create function platform_application_setting_guard() returns trigger
    language plpgsql
as $guard$
begin
    -- Messages name the table only, never a value.
    if tg_op = 'UPDATE' then
        if new.application is distinct from old.application or new.setting_key is distinct from old.setting_key then
            raise exception 'setting guard: the application and key of a row in % never change', tg_table_name
                using errcode = 'check_violation';
        end if;
        if old.deleted_at is not null then
            raise exception 'setting guard: a removed row in % is not changed', tg_table_name
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create trigger application_setting_guard before update on application_setting
    for each row execute function platform_application_setting_guard();
create trigger application_setting_row_guard before insert or update on application_setting
    for each row execute function platform_row_guard();
create trigger application_setting_tenant_guard before update on application_setting
    for each row execute function platform_tenant_guard();
alter table application_setting enable row level security;
alter table application_setting force row level security;
create policy application_setting_insert on application_setting for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy application_setting_select on application_setting for select
    using (tenant_id = (select platform_current_tenant()));
create policy application_setting_update on application_setting for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy application_setting_delete on application_setting for delete
    using (tenant_id = (select platform_current_tenant()));

create table inspection_action_plan_link (
    id                 uuid        primary key default uuidv7(),
    tenant_id          uuid        not null default platform_current_tenant() references tenant (id),
    template_id        uuid        not null,
    action_template_id uuid        not null,
    version            bigint      not null default 0,
    created_at         timestamptz not null default now(),
    created_by         uuid        not null,
    updated_at         timestamptz not null default now(),
    updated_by         uuid        not null,
    deleted_at         timestamptz,
    deleted_by         uuid,
    constraint inspection_action_plan_link_other check (template_id <> action_template_id)
);

create unique index inspection_action_plan_link_one_per_document
    on inspection_action_plan_link (tenant_id, template_id) where deleted_at is null;

create function platform_inspection_action_plan_link_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        if new.template_id is distinct from old.template_id then
            raise exception 'action plan link guard: the document of a row in % never changes', tg_table_name
                using errcode = 'check_violation';
        end if;
        if old.deleted_at is not null then
            raise exception 'action plan link guard: a removed row in % is not changed', tg_table_name
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create trigger inspection_action_plan_link_guard before update on inspection_action_plan_link
    for each row execute function platform_inspection_action_plan_link_guard();
create trigger inspection_action_plan_link_row_guard before insert or update on inspection_action_plan_link
    for each row execute function platform_row_guard();
create trigger inspection_action_plan_link_tenant_guard before update on inspection_action_plan_link
    for each row execute function platform_tenant_guard();
alter table inspection_action_plan_link enable row level security;
alter table inspection_action_plan_link force row level security;
create policy inspection_action_plan_link_insert on inspection_action_plan_link for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_action_plan_link_select on inspection_action_plan_link for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_action_plan_link_update on inspection_action_plan_link for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_action_plan_link_delete on inspection_action_plan_link for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table application_setting is
    'What an organization changed for an application in Setup, Application settings. The file of the application holds the defaults. ADR-0261.';
comment on table inspection_action_plan_link is
    'Which document is the Action plan form of a document. Set when the document is published to the plants. ADR-0261.';

-- The notice that tells a plant head a fill-in scored below the limit.
alter table notification drop constraint notification_kind_known;
alter table notification add constraint notification_kind_known check (kind in (
    'APPROVAL_TASK', 'APPROVAL_REMINDER', 'APPROVAL_DECIDED', 'WORKFLOW_NOTICE', 'TRIAL_NOTICE', 'CHANGE_REQUEST',
    'DOCUMENT_PUBLISHED', 'DOCUMENT_AT_PLANT', 'ACTION_PLAN_NEEDED'));
