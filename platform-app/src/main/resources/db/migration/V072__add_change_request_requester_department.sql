-- The department of the person who asked for a change, kept as it was when they asked (FA6A, ADR-0196). Expand only:
-- one nullable column, nothing is removed or renamed.
--
-- The words are those of the organization's lists at that moment ("Location, then department", several joined when the
-- person belonged to several departments), so a later change of a list, or of the groups a person is in, never rewrites
-- what a request said. It is null when the person was in no department group. It is text of the organization: it is
-- never written to a log, an audit record or an event.
--
-- What must hold even if the application had a bug: like the title, it is written once with the request and never
-- changes afterwards.

alter table inspection_change_request add column requester_department text;
alter table inspection_change_request add constraint inspection_change_request_department_length
    check (requester_department is null or char_length(requester_department) between 1 and 500);

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
            or new.requester_department is distinct from old.requester_department then
        raise exception 'change request guard: what a request of % says about its template, version, title and department is immutable',
            tg_table_name using errcode = 'check_violation';
    end if;
    if new.status is distinct from old.status and not (
            (old.status = 'OPEN' and new.status in ('ACCEPTED', 'DECLINED', 'DONE'))
            or (old.status = 'ACCEPTED' and new.status in ('DECLINED', 'DONE'))
            or (old.status = 'DONE' and new.status = 'OPEN')) then
        raise exception 'change request guard: a request of % cannot move from % to %', tg_table_name, old.status,
            new.status using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;
