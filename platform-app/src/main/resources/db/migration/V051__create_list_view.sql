-- Saved list views (Sprint 19, ADR-0130): the filter, the order and the columns of a list of records, kept under a
-- name so a person (or everyone) can open the same list again.
--
--   list_view   One saved view of one object. The definition is the closed shape of a record query (a filter tree, up to
--               two sort keys, a list of field API names): data, never code and never SQL; names are looked up in the
--               metadata and values are values when the list runs it. A view is NOT metadata: it is not published,
--               not part of a change set or a release, and it does not raise the metadata version. It is the
--               member's own preference ("PERSONAL", visible to the person who made it only) or the organization's
--               ("SHARED", visible to everyone who may read the object). The maker is created_by, which the row guard
--               keeps unchangeable, so "whose view is this" can never be rewritten.
--
-- What must hold even if the application had a bug:
--   * a view belongs to one organization (tenant_id, row level security forced) and to one object, which never changes;
--   * the scope is PERSONAL or SHARED and nothing else;
--   * two views a person sees cannot have the same name (a shared name is unique in the object; a personal name is
--     unique among the maker's own personal views of the object, so two people may each have a "Mine");
--   * the definition is a JSON object of bounded size.
--
-- Who may see or change a view is decided by the application (the maker, and the ability to share list views for a
-- shared one), on top of the row level security that keeps every view inside its organization.

create table list_view (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    label           text        not null,
    scope           text        not null,
    definition      jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint list_view_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint list_view_label_length check (label = btrim(label) and char_length(label) between 1 and 80),
    constraint list_view_scope_known check (scope in ('PERSONAL', 'SHARED')),
    constraint list_view_definition_is_object check (jsonb_typeof(definition) = 'object'),
    constraint list_view_definition_size check (octet_length(definition::text) <= 20000)
);

-- A shared view's name is unique in its object; a personal view's name is unique among its maker's own.
create unique index list_view_shared_label on list_view (tenant_id, object_api_name, lower(label))
    where deleted_at is null and scope = 'SHARED';
create unique index list_view_personal_label on list_view (tenant_id, object_api_name, created_by, lower(label))
    where deleted_at is null and scope = 'PERSONAL';

-- "The views of this object that this person sees": the shared ones and the person's own.
create index list_view_tenant_object on list_view (tenant_id, object_api_name, scope, created_by)
    where deleted_at is null;

create function platform_list_view_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and new.object_api_name is distinct from old.object_api_name then
        raise exception 'list view guard: a view does not move to another object'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_list_view_guard() is
    'A saved list view stays with the object it was made for (ADR-0130).';

create trigger list_view_row_guard before insert or update on list_view
    for each row execute function platform_row_guard();
create trigger list_view_tenant_guard before update on list_view
    for each row execute function platform_tenant_guard();
create trigger list_view_use_guard before update on list_view
    for each row execute function platform_list_view_guard();

alter table list_view enable row level security;
alter table list_view force row level security;
create policy list_view_insert on list_view for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy list_view_select on list_view for select
    using (tenant_id = (select platform_current_tenant()));
create policy list_view_update on list_view for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy list_view_delete on list_view for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table list_view is
    'A saved list view of one object: PERSONAL (its maker) or SHARED (everyone who may read the object). Tenant-scoped, row level security forced (ADR-0130).';
