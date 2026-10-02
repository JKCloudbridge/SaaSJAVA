-- Contract migration: the administrator marker is gone (Sprint 8, ADR-0043, ADR-0051).
--
-- Since Sprint 7 what a member may do comes from their profile, access policies and grants (ADR-0039); the yes/no marker of
-- Sprint 5 on a membership (membership.administrator) and on an invitation (invitation.administrator) decides nothing, and
-- this release no longer reads or writes either. This migration removes them, together with what depended on them.
-- founding_administrator is a historical fact, grants nothing and stays.
--
-- Order matters (the functions that name a column must stop naming it before the column goes):
--   1. an invitation that is still open and carried the marker (made before profiles existed) now carries the administrator
--      profile instead, so that accepting it still makes the person an administrator. An invitation a platform administrator
--      made for the first administrator of an organization carries no profile on purpose: the code gives that person the
--      administrator profile because the invitation says it was made by a platform administrator.
--   2. the invitation guard and the membership guard are replaced by the same rules without the marker;
--   3. the index, the constraint and the two columns are dropped.
--
-- Invitation and profile are tenant-scoped with forced row level security, which binds the owner too, and a migration has no
-- tenant: as in V013, V018 and V023, forcing is switched off for the one statement and back on in the same transaction. The
-- statement joins on the tenant column, so nothing is computed across organizations. Nothing happens on an empty database.

-- ---------------------------------------------------------------------------------------------------------------
-- 1. open invitations that carried the marker get the administrator profile
-- ---------------------------------------------------------------------------------------------------------------

alter table invitation no force row level security;
alter table profile no force row level security;

update invitation i
   set profile_id = (select p.id from profile p
                      where p.tenant_id = i.tenant_id and p.system_key = 'administrator' and p.deleted_at is null),
       version = i.version + 1, updated_by = '00000000-0000-0000-0000-000000000000'
 where i.status = 'OPEN' and i.administrator and i.profile_id is null and not i.invited_by_platform
   and exists (select 1 from profile p
                where p.tenant_id = i.tenant_id and p.system_key = 'administrator' and p.deleted_at is null);

alter table invitation force row level security;
alter table profile force row level security;

-- ---------------------------------------------------------------------------------------------------------------
-- 2. the guards without the marker
-- ---------------------------------------------------------------------------------------------------------------

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

create or replace function platform_membership_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'ACTIVE' then
            raise exception 'membership guard: a membership starts as ACTIVE' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if new.status <> old.status then
        if not ((old.status = 'ACTIVE' and new.status = 'DEACTIVATED')
                or (old.status = 'DEACTIVATED' and new.status = 'ACTIVE')) then
            raise exception 'membership guard: % to % is not a legal transition', old.status, new.status
                using errcode = 'check_violation';
        end if;
        new.status_changed_at := now();
    end if;
    return new;
end;
$guard$;

comment on function platform_membership_guard() is
    'Enforces the membership lifecycle (ADR-0026): legal status moves. The last member who can manage access stays: see platform_access_guard_check (ADR-0044).';

-- ---------------------------------------------------------------------------------------------------------------
-- 3. the marker itself
-- ---------------------------------------------------------------------------------------------------------------

drop index membership_tenant_admin;
alter table membership drop constraint membership_administrator_is_active;
alter table membership drop column administrator;
alter table invitation drop column administrator;

comment on table membership is
    'A user''s membership of an organization. Tenant-scoped (ADR-0015). ACTIVE or DEACTIVATED; what a member may do comes from their profile, access policies and grants (ADR-0039); founding_administrator is a historical fact. The membership_lookup system scope may read across tenants (ADR-0027).';
