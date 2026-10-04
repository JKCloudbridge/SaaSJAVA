-- The security version of an organization (Sprint 9, ADR-0053).
--
-- One counter per organization that goes up, in the same transaction, every time something changes that decides what a
-- member may do: the profiles, access policies, groups, permissions on data, member assignments and grants, the licences
-- members hold and the membership itself. The security cache (ADR-0053) keeps an answer together with the number it was
-- computed under and throws it away when the number has moved on, so a change takes effect on the next request on every
-- application instance, with no timer and no message that can get lost.
--
-- Triggers do the counting, not the Java services, so that no code path (a service, a migration script, a repair by
-- hand) can change those tables and forget the counter. A test lists the tables and fails when one has no trigger.
--
-- Not counted, on purpose: security_role (the role hierarchy decides which records can be seen, never an ability,
-- ADR-0040), licence_pool (the size of a pool decides no answer) and licence_type (changed only by migrations and by
-- adding a type, which changes no existing answer).
--
-- Tenant-scoped (ADR-0015): the row of an organization is visible to that organization only. The trigger function runs
-- with the rights of the caller, which is always inside the organization's own tenant context; a change of another
-- organization's row is refused by the tenant guard of the changed table before the counter is reached.
-- An organization without a row has had no change since this table exists: readers treat that as version -1, so the
-- first change (version 0) differs from it.

create table security_version (
    id         uuid        primary key default uuidv7(),
    tenant_id  uuid        not null default platform_current_tenant() references tenant (id),
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid
);

create unique index security_version_tenant on security_version (tenant_id) where deleted_at is null;

create trigger security_version_row_guard before insert or update on security_version
    for each row execute function platform_row_guard();
create trigger security_version_tenant_guard before update on security_version
    for each row execute function platform_tenant_guard();

alter table security_version enable row level security;
alter table security_version force row level security;
create policy security_version_insert on security_version for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy security_version_select on security_version for select
    using (tenant_id = (select platform_current_tenant()));
create policy security_version_update on security_version for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy security_version_delete on security_version for delete
    using (tenant_id = (select platform_current_tenant()));

create function platform_security_version_bump() returns trigger
    language plpgsql
as $bump$
declare
    organization uuid;
begin
    organization := case when tg_op = 'DELETE' then old.tenant_id else new.tenant_id end;
    insert into security_version (tenant_id, created_by, updated_by)
        values (organization, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
        on conflict (tenant_id) where deleted_at is null
        do update set version = security_version.version + 1,
                      updated_by = '00000000-0000-0000-0000-000000000000';
    return null;
end;
$bump$;

comment on function platform_security_version_bump() is
    'Adds one to the security version of the changed row''s organization (ADR-0053). Attached as <table>_security_version to every table that decides what a member may do.';

create trigger profile_security_version after insert or update or delete on profile
    for each row execute function platform_security_version_bump();
create trigger access_policy_security_version after insert or update or delete on access_policy
    for each row execute function platform_security_version_bump();
create trigger member_access_security_version after insert or update or delete on member_access
    for each row execute function platform_security_version_bump();
create trigger member_access_policy_security_version after insert or update or delete on member_access_policy
    for each row execute function platform_security_version_bump();
create trigger member_grant_security_version after insert or update or delete on member_grant
    for each row execute function platform_security_version_bump();
create trigger public_group_security_version after insert or update or delete on public_group
    for each row execute function platform_security_version_bump();
create trigger public_group_member_security_version after insert or update or delete on public_group_member
    for each row execute function platform_security_version_bump();
create trigger public_group_access_policy_security_version after insert or update or delete on public_group_access_policy
    for each row execute function platform_security_version_bump();
create trigger object_permission_security_version after insert or update or delete on object_permission
    for each row execute function platform_security_version_bump();
create trigger field_permission_security_version after insert or update or delete on field_permission
    for each row execute function platform_security_version_bump();
create trigger licence_assignment_security_version after insert or update or delete on licence_assignment
    for each row execute function platform_security_version_bump();
create trigger membership_security_version after insert or update or delete on membership
    for each row execute function platform_security_version_bump();

comment on table security_version is
    'One counter per organization, raised in the same transaction by every change that decides what a member may do (ADR-0053). Tenant-scoped.';
