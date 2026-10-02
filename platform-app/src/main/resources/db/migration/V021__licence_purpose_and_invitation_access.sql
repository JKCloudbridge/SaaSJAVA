-- Licences that follow profiles and access policies, and the invitation that carries them (Sprint 7, ADR-0039, ADR-0043).
--
-- 1. licence_assignment gets a PURPOSE. A member holds one licence for their PROFILE (the licence type of the profile) and
--    one licence for each licence-bound ACCESS POLICY assigned to them (source_id is that policy). Every row counts
--    against the pool of its type, so the pool numbers and the guards of Sprint 6 are unchanged. Existing rows are
--    profile licences (the column default), so nothing that exists changes meaning.
-- 2. invitation gets the profile, the role and the name the administrator entered. All are optional: an invitation made
--    before this migration, or by a platform administrator for a first administrator, carries none.

-- ---------------------------------------------------------------------------------------------------------------
-- 1. licence_assignment
-- ---------------------------------------------------------------------------------------------------------------

alter table licence_assignment add column purpose text not null default 'PROFILE';
alter table licence_assignment add column source_id uuid;
alter table licence_assignment add constraint licence_assignment_purpose_known
    check (purpose in ('PROFILE', 'ACCESS_POLICY'));
alter table licence_assignment add constraint licence_assignment_source_matches
    check ((purpose = 'PROFILE') = (source_id is null));

-- One profile licence per member, and one licence per member and licence-bound policy (a released row is soft deleted).
drop index licence_assignment_tenant_member;
create unique index licence_assignment_tenant_member on licence_assignment (tenant_id, membership_id)
    where purpose = 'PROFILE' and deleted_at is null;
create unique index licence_assignment_tenant_member_source on licence_assignment (tenant_id, membership_id, source_id)
    where purpose = 'ACCESS_POLICY' and deleted_at is null;

-- The guard of Sprint 6 (pool locked, a licence is free, the member is active), now also: an assignment keeps its
-- purpose and source, and a licence for an access policy is for a live policy of this organization that needs exactly
-- this licence type.
create or replace function platform_licence_assignment_guard() returns trigger
    language plpgsql
as $guard$
declare
    pool_quantity integer;
    used bigint;
    member_status text;
begin
    if tg_op = 'UPDATE' then
        if new.membership_id is distinct from old.membership_id
                or new.licence_type_id is distinct from old.licence_type_id
                or new.purpose is distinct from old.purpose
                or new.source_id is distinct from old.source_id then
            raise exception 'licence guard: an assignment does not change owner, type or purpose'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    select quantity into pool_quantity from licence_pool
     where tenant_id = new.tenant_id and licence_type_id = new.licence_type_id and deleted_at is null
       for update;
    if pool_quantity is null then
        raise exception 'licence guard: the organization has no pool of this licence type'
            using errcode = 'check_violation';
    end if;
    select count(*) into used from licence_assignment a
     where a.tenant_id = new.tenant_id and a.licence_type_id = new.licence_type_id and a.deleted_at is null;
    if used >= pool_quantity then
        raise exception 'licence guard: no free licence of this type' using errcode = 'check_violation';
    end if;
    select status into member_status from membership
     where id = new.membership_id and tenant_id = new.tenant_id and deleted_at is null;
    if member_status is distinct from 'ACTIVE' then
        raise exception 'licence guard: only an active member can hold a licence' using errcode = 'check_violation';
    end if;
    if new.purpose = 'ACCESS_POLICY' and not exists (
            select 1 from access_policy p
             where p.id = new.source_id and p.tenant_id = new.tenant_id and p.deleted_at is null
               and p.required_licence_type_id = new.licence_type_id) then
        raise exception 'licence guard: the access policy does not need a licence of this type'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_licence_assignment_guard() is
    'Locks the pool row, refuses an assignment when no licence is free or the member is not active, keeps purpose and source, and ties a policy licence to a policy that needs that type (ADR-0032, ADR-0039).';

comment on table licence_assignment is
    'A licence held by an active member, for their profile or for one licence-bound access policy; released by soft delete. Tenant-scoped (ADR-0015). Counts and limits assignments only; permissions are decided elsewhere (ADR-0032, ADR-0039).';

-- ---------------------------------------------------------------------------------------------------------------
-- 2. invitation
-- ---------------------------------------------------------------------------------------------------------------

alter table invitation add column profile_id uuid references profile (id);
alter table invitation add column role_id uuid references security_role (id);
alter table invitation add column display_name text;
alter table invitation add constraint invitation_display_name_length
    check (display_name is null or char_length(display_name) between 1 and 200);

-- The lifecycle guard of Sprint 5, now also: what the administrator entered is read-only once the invitation is closed,
-- and the profile and the role are rows of this organization (a foreign key check bypasses row level security).
create or replace function platform_invitation_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.profile_id is not null and not exists (select 1 from profile p where p.id = new.profile_id
            and p.tenant_id = new.tenant_id and p.deleted_at is null) then
        raise exception 'invitation guard: the profile must be one of this organization'
            using errcode = 'check_violation';
    end if;
    if new.role_id is not null and not exists (select 1 from security_role r where r.id = new.role_id
            and r.tenant_id = new.tenant_id and r.deleted_at is null) then
        raise exception 'invitation guard: the role must be one of this organization'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if new.status <> 'OPEN' then
            raise exception 'invitation guard: an invitation starts as OPEN' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.status <> old.status and not (old.status = 'OPEN' and new.status in ('ACCEPTED', 'REVOKED')) then
        raise exception 'invitation guard: % to % is not a legal transition', old.status, new.status
            using errcode = 'check_violation';
    end if;
    if old.status <> 'OPEN' and (new.email is distinct from old.email
            or new.administrator is distinct from old.administrator
            or new.founding_administrator is distinct from old.founding_administrator
            or new.profile_id is distinct from old.profile_id
            or new.role_id is distinct from old.role_id
            or new.display_name is distinct from old.display_name) then
        raise exception 'invitation guard: a closed invitation is read-only' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_invitation_guard() is
    'Enforces the invitation lifecycle (ADR-0028): OPEN to ACCEPTED or REVOKED, nothing else, a closed invitation does not change, and its profile and role are rows of the same organization (ADR-0043).';

comment on column invitation.administrator is
    'Sprint 5 marker; no longer read from Sprint 7 (the profile decides). Dropped in a later contract migration.';
comment on column membership.administrator is
    'Sprint 5 stop-gap; no longer read from Sprint 7 (the profile and its abilities decide, ADR-0039). Dropped in a later contract migration.';
