-- Change requests are reviewed by the approval engine (Functional track FA6E, ADR-0199). Expand only: one nullable
-- column, two indexes, and the guard function replaced to know the new column. Nothing is removed or renamed.
--
-- Raising a request now also makes a small record of the shipped object InspectionChangeRequest, which exists only to
-- give the approval engine a record to review; its identifier is kept here. It is null for a request raised before this
-- release and for a request raised in an organization that has no approval process for requests: those are answered
-- directly, as before.
--
-- What must hold even if the application had a bug:
--   * the link never changes once written, and is unique (a record reviews exactly one request);
--   * a request that was turned into a version and whose version was not used goes back to "accepted" when it has a
--     review record (its review already happened, so it must not wait for another), and to "open" when it has none,
--     as before. The other moves are unchanged.

alter table inspection_change_request add column header_id uuid;

create unique index inspection_change_request_header
    on inspection_change_request (tenant_id, header_id) where header_id is not null and deleted_at is null;
-- The list across templates, newest first (the tab of change requests), and the open count of a template: a request is
-- open to action while it is open or accepted.
create index inspection_change_request_tenant_newest
    on inspection_change_request (tenant_id, created_at desc, id desc) where deleted_at is null;
create index inspection_change_request_tenant_to_act
    on inspection_change_request (tenant_id, template_id) where status in ('OPEN', 'ACCEPTED') and deleted_at is null;

create or replace function platform_inspection_change_request_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'OPEN' then
            raise exception 'change request guard: a request of % starts open', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- tg_op = 'UPDATE'
    if new.deleted_at is not null then
        raise exception 'change request guard: the requests of % are kept', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.template_id is distinct from old.template_id or new.version_no is distinct from old.version_no
            or new.title is distinct from old.title or new.created_by is distinct from old.created_by
            or new.requester_department is distinct from old.requester_department
            or new.header_id is distinct from old.header_id then
        raise exception 'change request guard: what a request of % says about its template, version, title, department and review record is immutable',
            tg_table_name using errcode = 'check_violation';
    end if;
    if new.status is distinct from old.status and not (
            (old.status = 'OPEN' and new.status in ('ACCEPTED', 'DECLINED', 'DONE'))
            or (old.status = 'ACCEPTED' and new.status in ('DECLINED', 'DONE'))
            or (old.status = 'DONE' and new.status = 'OPEN' and old.header_id is null)
            or (old.status = 'DONE' and new.status = 'ACCEPTED' and old.header_id is not null)) then
        raise exception 'change request guard: a request of % cannot move from % to %', tg_table_name, old.status,
            new.status using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;
