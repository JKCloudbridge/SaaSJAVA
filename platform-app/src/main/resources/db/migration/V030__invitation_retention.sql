-- Retention of closed invitations (Sprint 9, ADR-0057).
--
-- A closed invitation (accepted, revoked, or expired for a long time) keeps the address and the name of the person who
-- was invited. Nothing needs them after a short while, so the platform's clean-up job blanks them: the address becomes
-- 'anonymised-<id>' (still a valid value for the format check and still unique in the organization) and the name is
-- removed. The row itself stays, so the organization's counts and history of invitations do not change.
--
-- anonymised_at marks the invitations that were blanked and is the only thing that lets the guard accept a change of a
-- closed invitation: nothing else about a closed invitation can change. An invitation that was still open but expired
-- long ago is closed (REVOKED) in the same statement, because an open one can still be sent again.

alter table invitation add column anonymised_at timestamptz;

alter table invitation add constraint invitation_anonymised_only_when_closed
    check (anonymised_at is null or status <> 'OPEN');

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
        if new.anonymised_at is not null then
            raise exception 'invitation guard: a new invitation is not anonymised' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.status <> old.status and not (old.status = 'OPEN' and new.status in ('ACCEPTED', 'REVOKED')) then
        raise exception 'invitation guard: % to % is not a legal transition', old.status, new.status
            using errcode = 'check_violation';
    end if;
    if old.anonymised_at is not null and (new.anonymised_at is distinct from old.anonymised_at
            or new.email is distinct from old.email or new.display_name is distinct from old.display_name) then
        raise exception 'invitation guard: an anonymised invitation does not change' using errcode = 'check_violation';
    end if;
    if old.anonymised_at is null and new.anonymised_at is not null then
        -- The one allowed change of the address and the name of a closed invitation: blanking them.
        if new.email <> 'anonymised-' || old.id::text or new.display_name is not null then
            raise exception 'invitation guard: anonymising blanks the address and the name, nothing else'
                using errcode = 'check_violation';
        end if;
    elsif old.status <> 'OPEN' and (new.email is distinct from old.email
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
    'Enforces the invitation lifecycle (ADR-0028): OPEN to ACCEPTED or REVOKED, nothing else, a closed invitation does not change except that its address and name are blanked once by the retention job (ADR-0057), and its profile and role are rows of the same organization (ADR-0043).';

create index invitation_tenant_retention on invitation (tenant_id, resolved_at)
    where anonymised_at is null and deleted_at is null;

comment on column invitation.anonymised_at is
    'When the retention job blanked the address and the name of this closed invitation (ADR-0057).';
