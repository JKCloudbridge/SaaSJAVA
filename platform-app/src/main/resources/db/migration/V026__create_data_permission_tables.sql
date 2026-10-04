-- Object permissions and field permissions (Sprint 8, ADR-0049, ADR-0050).
--
-- What a member may do with the data of an object (read, create, update, delete, and the two flags view-all and modify-all
-- that matter once record-level rules exist) and with one field of it (read, edit) comes from the same three containers as
-- the abilities, and is combined by the same union (ADR-0040): the PROFILE, the ACCESS POLICIES (also those given through a
-- group) and the individual grants of the member. A row holds one container's actions on one object, or on one field.
--
-- An object or a field is named by a key (for example 'object-a' and 'object-a.field-a'), not by a row of a table: objects
-- and fields themselves arrive in Milestone 3 (Sprint 10), which then says which keys exist (the ObjectCatalog contract of
-- the security module). The database stores the key as text and checks only its shape; a key that no longer exists is
-- ignored when permissions are computed, never an error.
--
-- Both tables are tenant-scoped (ADR-0015, ADR-0049): tenant_id, row level security enabled and forced, a policy on
-- platform_current_tenant(), the tenant guard trigger and a tenant-first index. A row belongs to exactly one container
-- (profile, access policy or member) of the same organization; the guard below checks it, because a foreign key check
-- bypasses row level security. The last-member-who-can-manage-access rule (ADR-0044) is not touched: these tables hold no
-- ability.

create table object_permission (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    profile_id       uuid        references profile (id),
    access_policy_id uuid        references access_policy (id),
    membership_id    uuid        references membership (id),
    object_key       text        not null,
    actions          text[]      not null,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint object_permission_one_holder check (num_nonnulls(profile_id, access_policy_id, membership_id) = 1),
    constraint object_permission_key_shape check (object_key ~ '^[a-z][a-z0-9_-]{0,59}$'),
    constraint object_permission_actions_known
        check (cardinality(actions) between 1 and 6
               and actions <@ array['read', 'create', 'update', 'delete', 'view-all', 'modify-all'])
);

create unique index object_permission_tenant_profile on object_permission (tenant_id, profile_id, object_key)
    where profile_id is not null and deleted_at is null;
create unique index object_permission_tenant_policy on object_permission (tenant_id, access_policy_id, object_key)
    where access_policy_id is not null and deleted_at is null;
create unique index object_permission_tenant_member on object_permission (tenant_id, membership_id, object_key)
    where membership_id is not null and deleted_at is null;

create table field_permission (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    profile_id       uuid        references profile (id),
    access_policy_id uuid        references access_policy (id),
    membership_id    uuid        references membership (id),
    field_key        text        not null,
    actions          text[]      not null,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint field_permission_one_holder check (num_nonnulls(profile_id, access_policy_id, membership_id) = 1),
    constraint field_permission_key_shape
        check (field_key ~ '^[a-z][a-z0-9_-]{0,59}[.][a-z][a-z0-9_-]{0,59}$'),
    constraint field_permission_actions_known
        check (cardinality(actions) between 1 and 2 and actions <@ array['read', 'edit'])
);

create unique index field_permission_tenant_profile on field_permission (tenant_id, profile_id, field_key)
    where profile_id is not null and deleted_at is null;
create unique index field_permission_tenant_policy on field_permission (tenant_id, access_policy_id, field_key)
    where access_policy_id is not null and deleted_at is null;
create unique index field_permission_tenant_member on field_permission (tenant_id, membership_id, field_key)
    where membership_id is not null and deleted_at is null;

-- The container of a permission row is a live row of this organization (a member: an active member), and a row never
-- moves to another container.
create function platform_data_permission_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.profile_id is distinct from old.profile_id
            or new.access_policy_id is distinct from old.access_policy_id
            or new.membership_id is distinct from old.membership_id) then
        raise exception 'data permission guard: a permission does not change its container'
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        return new;
    end if;
    if new.profile_id is not null and not exists (select 1 from profile p where p.id = new.profile_id
            and p.tenant_id = new.tenant_id and p.deleted_at is null) then
        raise exception 'data permission guard: the profile must be one of this organization'
            using errcode = 'check_violation';
    end if;
    if new.access_policy_id is not null and not exists (select 1 from access_policy p
            where p.id = new.access_policy_id and p.tenant_id = new.tenant_id and p.deleted_at is null) then
        raise exception 'data permission guard: the access policy must be one of this organization'
            using errcode = 'check_violation';
    end if;
    if new.membership_id is not null and not exists (select 1 from membership m where m.id = new.membership_id
            and m.tenant_id = new.tenant_id and m.status = 'ACTIVE' and m.deleted_at is null) then
        raise exception 'data permission guard: only an active member of this organization can be given a permission'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_data_permission_guard() is
    'The container of an object or field permission is a live profile, access policy or active member of the same organization and never changes (ADR-0049).';

create trigger object_permission_row_guard before insert or update on object_permission
    for each row execute function platform_row_guard();
create trigger object_permission_tenant_guard before update on object_permission
    for each row execute function platform_tenant_guard();
create trigger object_permission_container_guard before insert or update on object_permission
    for each row execute function platform_data_permission_guard();

create trigger field_permission_row_guard before insert or update on field_permission
    for each row execute function platform_row_guard();
create trigger field_permission_tenant_guard before update on field_permission
    for each row execute function platform_tenant_guard();
create trigger field_permission_container_guard before insert or update on field_permission
    for each row execute function platform_data_permission_guard();

alter table object_permission enable row level security;
alter table object_permission force row level security;
create policy object_permission_insert on object_permission for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy object_permission_select on object_permission for select
    using (tenant_id = (select platform_current_tenant()));
create policy object_permission_update on object_permission for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy object_permission_delete on object_permission for delete
    using (tenant_id = (select platform_current_tenant()));

alter table field_permission enable row level security;
alter table field_permission force row level security;
create policy field_permission_insert on field_permission for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy field_permission_select on field_permission for select
    using (tenant_id = (select platform_current_tenant()));
create policy field_permission_update on field_permission for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy field_permission_delete on field_permission for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table object_permission is
    'What one container (profile, access policy or member) allows on one object, by key: read, create, update, delete, view-all, modify-all. Tenant-scoped (ADR-0015, ADR-0049).';
comment on table field_permission is
    'What one container (profile, access policy or member) allows on one field, by key object.field: read, edit. Tenant-scoped (ADR-0015, ADR-0049).';
