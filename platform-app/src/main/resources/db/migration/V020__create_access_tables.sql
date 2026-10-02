-- Profiles, role hierarchy, access policies and what members hold (Sprint 7, ADR-0039 to ADR-0043).
--
-- What a member may do is the union of three things (ADR-0040): the abilities of their PROFILE (exactly one per member,
-- and a profile belongs to one licence type), the abilities of the ACCESS POLICIES assigned to them, and abilities
-- granted to them individually. The ROLE hierarchy never decides abilities: it only says, later, which records a member
-- may see (Sprint 8 and Milestone 4 read it); here it is a tree per organization and a place to hang each member.
--
-- An ability is a short key such as 'members.invite'. The catalogue of keys is in the code (the database stores the text
-- and checks only its shape, so a key that a later release drops is ignored, never an error).
--
-- Every table here is tenant-scoped (ADR-0015, ADR-0041): the organization edits all of it, so each has tenant_id, row
-- level security enabled and forced, a policy on platform_current_tenant(), the tenant guard trigger and a tenant-first
-- index. A row never points at a row of another organization (the guards below check it, because a foreign key check
-- bypasses row level security).

-- ---------------------------------------------------------------------------------------------------------------
-- profile
-- ---------------------------------------------------------------------------------------------------------------

create table profile (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    name            text        not null,
    description     text        not null default '',
    licence_type_id uuid        not null references licence_type (id),
    abilities       text[]      not null default '{}',
    system_key      text,
    full_access     boolean     not null default false,
    is_default      boolean     not null default false,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint profile_name_length check (char_length(name) between 1 and 80),
    constraint profile_description_length check (char_length(description) <= 500),
    constraint profile_system_key_known check (system_key is null or system_key in ('administrator', 'member')),
    constraint profile_full_access_only_administrator check (not full_access or system_key = 'administrator'),
    constraint profile_abilities_shape check (array_to_string(abilities, ',') ~ '^([a-z][a-z0-9.-]{0,59}(,|$))*$')
);

create unique index profile_tenant_name on profile (tenant_id, lower(name)) where deleted_at is null;
create unique index profile_tenant_system_key on profile (tenant_id, system_key)
    where system_key is not null and deleted_at is null;
create unique index profile_tenant_default on profile (tenant_id) where is_default and deleted_at is null;
create index profile_tenant_licence_type on profile (tenant_id, licence_type_id) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- access_policy
-- ---------------------------------------------------------------------------------------------------------------

create table access_policy (
    id                        uuid        primary key default uuidv7(),
    tenant_id                 uuid        not null default platform_current_tenant() references tenant (id),
    name                      text        not null,
    description               text        not null default '',
    abilities                 text[]      not null default '{}',
    required_licence_type_id  uuid        references licence_type (id),
    version                   bigint      not null default 0,
    created_at                timestamptz not null default now(),
    created_by                uuid        not null,
    updated_at                timestamptz not null default now(),
    updated_by                uuid        not null,
    deleted_at                timestamptz,
    deleted_by                uuid,
    constraint access_policy_name_length check (char_length(name) between 1 and 80),
    constraint access_policy_description_length check (char_length(description) <= 500),
    constraint access_policy_abilities_shape
        check (array_to_string(abilities, ',') ~ '^([a-z][a-z0-9.-]{0,59}(,|$))*$')
);

create unique index access_policy_tenant_name on access_policy (tenant_id, lower(name)) where deleted_at is null;
create index access_policy_tenant_licence_type on access_policy (tenant_id, required_licence_type_id)
    where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- security_role: the hierarchy that only decides visibility
-- ---------------------------------------------------------------------------------------------------------------

create table security_role (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    name        text        not null,
    description text        not null default '',
    parent_id   uuid        references security_role (id),
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint security_role_name_length check (char_length(name) between 1 and 80),
    constraint security_role_description_length check (char_length(description) <= 500),
    constraint security_role_not_own_parent check (parent_id is null or parent_id <> id)
);

create unique index security_role_tenant_name on security_role (tenant_id, lower(name)) where deleted_at is null;
create index security_role_tenant_parent on security_role (tenant_id, parent_id) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- what members hold
-- ---------------------------------------------------------------------------------------------------------------

-- One row per member: their profile and (optionally) their place in the role hierarchy.
create table member_access (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    membership_id uuid        not null references membership (id),
    profile_id    uuid        not null references profile (id),
    role_id       uuid        references security_role (id),
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid
);

create unique index member_access_tenant_member on member_access (tenant_id, membership_id) where deleted_at is null;
create index member_access_tenant_profile on member_access (tenant_id, profile_id) where deleted_at is null;
create index member_access_tenant_role on member_access (tenant_id, role_id) where deleted_at is null;

create table member_access_policy (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    membership_id    uuid        not null references membership (id),
    access_policy_id uuid        not null references access_policy (id),
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid
);

create unique index member_access_policy_tenant_member_policy
    on member_access_policy (tenant_id, membership_id, access_policy_id) where deleted_at is null;
create index member_access_policy_tenant_policy on member_access_policy (tenant_id, access_policy_id)
    where deleted_at is null;

-- An ability given to one member directly, with the short note why (kept for the people who manage access).
create table member_grant (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    membership_id uuid        not null references membership (id),
    ability       text        not null,
    reason        text        not null default '',
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid,
    constraint member_grant_ability_shape check (ability ~ '^[a-z][a-z0-9.-]{0,59}$'),
    constraint member_grant_reason_length check (char_length(reason) <= 200)
);

create unique index member_grant_tenant_member_ability on member_grant (tenant_id, membership_id, ability)
    where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- guards: what must hold even if the application had a bug
-- ---------------------------------------------------------------------------------------------------------------

-- A profile: the system profiles stay, the default profile stays, a profile in use is not removed and does not change
-- its licence type (the licences of its members were taken from that type).
create function platform_profile_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if not exists (select 1 from licence_type where id = new.licence_type_id and deleted_at is null) then
            raise exception 'profile guard: unknown licence type' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.system_key is distinct from old.system_key or new.full_access is distinct from old.full_access then
        raise exception 'profile guard: a system profile keeps its kind' using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null and old.deleted_at is null then
        if old.system_key is not null then
            raise exception 'profile guard: a system profile cannot be removed' using errcode = 'check_violation';
        end if;
        if old.is_default then
            raise exception 'profile guard: the default profile cannot be removed' using errcode = 'check_violation';
        end if;
        if exists (select 1 from member_access ma
                    where ma.tenant_id = old.tenant_id and ma.profile_id = old.id and ma.deleted_at is null) then
            raise exception 'profile guard: a profile in use cannot be removed' using errcode = 'check_violation';
        end if;
    end if;
    if new.licence_type_id is distinct from old.licence_type_id and exists (
            select 1 from member_access ma
             where ma.tenant_id = old.tenant_id and ma.profile_id = old.id and ma.deleted_at is null) then
        raise exception 'profile guard: a profile in use keeps its licence type' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_profile_guard() is
    'System profiles stay and keep their kind, the default profile stays, a profile in use is not removed and keeps its licence type (ADR-0039).';

-- An access policy in use is not removed and does not change the licence type it needs.
create function platform_access_policy_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        return new;
    end if;
    if new.required_licence_type_id is distinct from old.required_licence_type_id and exists (
            select 1 from member_access_policy p
             where p.tenant_id = old.tenant_id and p.access_policy_id = old.id and p.deleted_at is null) then
        raise exception 'access policy guard: a policy in use keeps its licence type' using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null and old.deleted_at is null and exists (
            select 1 from member_access_policy p
             where p.tenant_id = old.tenant_id and p.access_policy_id = old.id and p.deleted_at is null) then
        raise exception 'access policy guard: a policy in use cannot be removed' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_access_policy_guard() is
    'An access policy in use is not removed and keeps the licence type it needs (ADR-0039).';

-- The role hierarchy is a tree: a role is not its own ancestor, its parent is a live role of the same organization, and a
-- role in use or with children is not removed. The check walks up from the new parent under one lock per organization,
-- so two administrators moving roles at the same moment cannot together make a loop.
create function platform_security_role_guard() returns trigger
    language plpgsql
as $guard$
declare
    cursor_id uuid;
    steps integer := 0;
begin
    perform pg_advisory_xact_lock(hashtextextended('security-roles:' || new.tenant_id::text, 0));
    if new.parent_id is not null then
        if not exists (select 1 from security_role r
                        where r.id = new.parent_id and r.tenant_id = new.tenant_id and r.deleted_at is null) then
            raise exception 'role guard: the parent must be a role of this organization'
                using errcode = 'check_violation';
        end if;
        cursor_id := new.parent_id;
        while cursor_id is not null loop
            if cursor_id = new.id then
                raise exception 'role guard: a role cannot be its own ancestor' using errcode = 'check_violation';
            end if;
            steps := steps + 1;
            if steps > 50 then
                raise exception 'role guard: the hierarchy is too deep' using errcode = 'check_violation';
            end if;
            select r.parent_id into cursor_id from security_role r
             where r.id = cursor_id and r.tenant_id = new.tenant_id and r.deleted_at is null;
        end loop;
    end if;
    if tg_op = 'UPDATE' and new.deleted_at is not null and old.deleted_at is null then
        if exists (select 1 from security_role c
                    where c.tenant_id = old.tenant_id and c.parent_id = old.id and c.deleted_at is null) then
            raise exception 'role guard: a role with sub-roles cannot be removed' using errcode = 'check_violation';
        end if;
        if exists (select 1 from member_access ma
                    where ma.tenant_id = old.tenant_id and ma.role_id = old.id and ma.deleted_at is null) then
            raise exception 'role guard: a role in use cannot be removed' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_security_role_guard() is
    'The role hierarchy is a tree: no loop, parent in the same organization, a role in use is not removed (ADR-0042).';

-- What a member holds: the member is an active member of this organization and the thing assigned is a live row of this
-- organization (a foreign key check bypasses row level security, so this is what keeps the organizations apart).
create function platform_member_access_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and new.membership_id is distinct from old.membership_id then
        raise exception 'access guard: an assignment does not change owner' using errcode = 'check_violation';
    end if;
    if new.deleted_at is null and (tg_op = 'INSERT' or new.membership_id is distinct from old.membership_id) then
        if not exists (select 1 from membership m where m.id = new.membership_id and m.tenant_id = new.tenant_id
                          and m.status = 'ACTIVE' and m.deleted_at is null) then
            raise exception 'access guard: only an active member of this organization can hold access'
                using errcode = 'check_violation';
        end if;
    end if;
    if tg_table_name = 'member_access' then
        if new.deleted_at is null and not exists (select 1 from profile p where p.id = new.profile_id
                and p.tenant_id = new.tenant_id and p.deleted_at is null) then
            raise exception 'access guard: the profile must be one of this organization'
                using errcode = 'check_violation';
        end if;
        if new.role_id is not null and not exists (select 1 from security_role r where r.id = new.role_id
                and r.tenant_id = new.tenant_id and r.deleted_at is null) then
            raise exception 'access guard: the role must be one of this organization'
                using errcode = 'check_violation';
        end if;
    elsif tg_table_name = 'member_access_policy' then
        if tg_op = 'INSERT' and not exists (select 1 from access_policy p where p.id = new.access_policy_id
                and p.tenant_id = new.tenant_id and p.deleted_at is null) then
            raise exception 'access guard: the policy must be one of this organization'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_member_access_guard() is
    'An assignment belongs to an active member and points only at rows of the same organization (ADR-0039).';

-- ---------------------------------------------------------------------------------------------------------------
-- triggers, row level security, comments
-- ---------------------------------------------------------------------------------------------------------------

create trigger profile_row_guard before insert or update on profile
    for each row execute function platform_row_guard();
create trigger profile_tenant_guard before update on profile
    for each row execute function platform_tenant_guard();
create trigger profile_use_guard before insert or update on profile
    for each row execute function platform_profile_guard();

create trigger access_policy_row_guard before insert or update on access_policy
    for each row execute function platform_row_guard();
create trigger access_policy_tenant_guard before update on access_policy
    for each row execute function platform_tenant_guard();
create trigger access_policy_use_guard before insert or update on access_policy
    for each row execute function platform_access_policy_guard();

create trigger security_role_row_guard before insert or update on security_role
    for each row execute function platform_row_guard();
create trigger security_role_tenant_guard before update on security_role
    for each row execute function platform_tenant_guard();
create trigger security_role_tree_guard before insert or update on security_role
    for each row execute function platform_security_role_guard();

create trigger member_access_row_guard before insert or update on member_access
    for each row execute function platform_row_guard();
create trigger member_access_tenant_guard before update on member_access
    for each row execute function platform_tenant_guard();
create trigger member_access_member_guard before insert or update on member_access
    for each row execute function platform_member_access_guard();

create trigger member_access_policy_row_guard before insert or update on member_access_policy
    for each row execute function platform_row_guard();
create trigger member_access_policy_tenant_guard before update on member_access_policy
    for each row execute function platform_tenant_guard();
create trigger member_access_policy_member_guard before insert or update on member_access_policy
    for each row execute function platform_member_access_guard();

create trigger member_grant_row_guard before insert or update on member_grant
    for each row execute function platform_row_guard();
create trigger member_grant_tenant_guard before update on member_grant
    for each row execute function platform_tenant_guard();
create trigger member_grant_member_guard before insert or update on member_grant
    for each row execute function platform_member_access_guard();

alter table profile enable row level security;
alter table profile force row level security;
create policy profile_insert on profile for insert with check (tenant_id = (select platform_current_tenant()));
create policy profile_select on profile for select using (tenant_id = (select platform_current_tenant()));
create policy profile_update on profile for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy profile_delete on profile for delete using (tenant_id = (select platform_current_tenant()));

alter table access_policy enable row level security;
alter table access_policy force row level security;
create policy access_policy_insert on access_policy for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy access_policy_select on access_policy for select
    using (tenant_id = (select platform_current_tenant()));
create policy access_policy_update on access_policy for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy access_policy_delete on access_policy for delete
    using (tenant_id = (select platform_current_tenant()));

alter table security_role enable row level security;
alter table security_role force row level security;
create policy security_role_insert on security_role for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy security_role_select on security_role for select
    using (tenant_id = (select platform_current_tenant()));
create policy security_role_update on security_role for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy security_role_delete on security_role for delete
    using (tenant_id = (select platform_current_tenant()));

alter table member_access enable row level security;
alter table member_access force row level security;
create policy member_access_insert on member_access for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy member_access_select on member_access for select
    using (tenant_id = (select platform_current_tenant()));
create policy member_access_update on member_access for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy member_access_delete on member_access for delete
    using (tenant_id = (select platform_current_tenant()));

alter table member_access_policy enable row level security;
alter table member_access_policy force row level security;
create policy member_access_policy_insert on member_access_policy for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy member_access_policy_select on member_access_policy for select
    using (tenant_id = (select platform_current_tenant()));
create policy member_access_policy_update on member_access_policy for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy member_access_policy_delete on member_access_policy for delete
    using (tenant_id = (select platform_current_tenant()));

alter table member_grant enable row level security;
alter table member_grant force row level security;
create policy member_grant_insert on member_grant for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy member_grant_select on member_grant for select
    using (tenant_id = (select platform_current_tenant()));
create policy member_grant_update on member_grant for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy member_grant_delete on member_grant for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table profile is
    'A named base set of abilities of a member; belongs to one licence type; system profiles (administrator, member) cannot be removed. Tenant-scoped (ADR-0015, ADR-0039).';
comment on table access_policy is
    'Additional abilities added to a member, optionally needing a licence of one type. Tenant-scoped (ADR-0015, ADR-0039).';
comment on table security_role is
    'A node of the role hierarchy that only decides which records a member may see later; never decides abilities. Tenant-scoped (ADR-0015, ADR-0042).';
comment on table member_access is
    'The profile and the role of one active member. Tenant-scoped (ADR-0015, ADR-0039).';
comment on table member_access_policy is
    'An access policy assigned to a member. Tenant-scoped (ADR-0015, ADR-0039).';
comment on table member_grant is
    'An ability given to one member directly, with a short bounded note. Tenant-scoped (ADR-0015, ADR-0040).';
