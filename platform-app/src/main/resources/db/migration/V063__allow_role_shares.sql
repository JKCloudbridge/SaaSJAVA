-- Shares of a record to a role (Functional track FA2B, ADR-0178 and ADR-0185). Expand only: nothing that exists changes.
--
--   record_share_grantee_kind_known   The kinds of grantee a share may name. Until now a user or a public group; now also
--                                     ROLE (the members whose own role it is) and ROLE_AND_SUBORDINATES (the members of
--                                     that role and of every role below it). The new check is added not valid and then
--                                     validated, and only then is the old one dropped, so there is no moment at which a
--                                     row is unchecked and no long lock is taken on a table that is being read.
--   platform_record_share_guard       A share still never moves to another record or grantee. The one change it now
--                                     allows is the kind of a share to a role, between ROLE and ROLE_AND_SUBORDINATES
--                                     for the same role: the unique index keeps one share of a record per grantee, so
--                                     sharing the same role "and below" after sharing it alone has to change the row.
--
-- No table is added, so there is no new row level security policy and no entry in the isolation harness. The security
-- version is not raised by a share (ADR-0115, point 5): the statement reads the live rows.

alter table record_share add constraint record_share_grantee_kind_known
    check (grantee_type in ('USER', 'GROUP', 'ROLE', 'ROLE_AND_SUBORDINATES')) not valid;
alter table record_share validate constraint record_share_grantee_kind_known;
alter table record_share drop constraint record_share_grantee_known;

create or replace function platform_record_share_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.object_api_name is distinct from old.object_api_name
            or new.record_id is distinct from old.record_id
            or new.grantee_id is distinct from old.grantee_id
            or (new.grantee_type is distinct from old.grantee_type
                and not (old.grantee_type in ('ROLE', 'ROLE_AND_SUBORDINATES')
                         and new.grantee_type in ('ROLE', 'ROLE_AND_SUBORDINATES')))) then
        raise exception 'share guard: a share does not move to another record or grantee'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' and not exists (
            select 1 from object_record r where r.id = new.record_id and r.tenant_id = new.tenant_id
               and r.object_api_name = new.object_api_name) then
        raise exception 'share guard: a share belongs to a record of this organization and object'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_record_share_guard() is
    'A share names a record of its own organization and object and never moves; a share to a role may change between ROLE and ROLE_AND_SUBORDINATES (ADR-0117, ADR-0185).';

comment on table record_share is
    'A record shared with one user, one public group or one role (alone or with the roles below it), with its level (1 read, 2 read and write). Tenant-scoped, row level security forced (ADR-0117, ADR-0185).';
