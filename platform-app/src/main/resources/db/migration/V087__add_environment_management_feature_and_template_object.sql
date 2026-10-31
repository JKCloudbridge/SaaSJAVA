-- The Environment Management application (Functional track FA13B, ADR-0226): its plan feature, and the object column that
-- keeps two sets of documents apart in the two tables that are read across all templates of an organization.
--
-- 1. The plan feature `environment-management`. Like the features of V081 and V086 it is in no plan by default: a
--    platform administrator adds it to a plan, or gives one organization an override, in the console. The key is
--    permanent (plans, overrides and the audit trail point at it); the name is what the console shows.
--
-- 2. Two applications of one kind (the Quality Management System and Environment Management) have separate templates,
--    fill-ins and change requests, each set in objects of its own. Every table of the cluster is keyed by the identifier
--    of a record, so it is separate by construction, except the two that are read for the whole organization:
--      * inspection_change_request: the list of requests across templates (newest first, by status, by search);
--      * inspection_template_event: the timeline of the home page and its monthly counts.
--    Each gets the API name of the template object the row belongs to, so those reads can ask for one set only and one
--    set's rows can never fill another's page or bound. The application always writes it. The default is the object of the
--    only set that existed before this release, so a row written before it is read as that set's without a rewrite
--    (the rows are append-only and are not touched).
--
-- Expand only: one new row, two new columns with a default, three indexes, and the guard function replaced to know the
-- column. Nothing is removed or renamed.

insert into feature (key, name, created_by, updated_by)
values ('environment-management', 'Environment Management', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000');

alter table inspection_change_request
    add column template_object text not null default 'InspectionTemplate',
    add constraint inspection_change_request_object_shape check (template_object ~ '^[A-Z][A-Za-z0-9]{0,59}$');

alter table inspection_template_event
    add column template_object text not null default 'InspectionTemplate',
    add constraint inspection_template_event_object_shape check (template_object ~ '^[A-Z][A-Za-z0-9]{0,59}$');

-- The list across templates and the requests to act on, narrowed to one set first.
create index inspection_change_request_tenant_object_newest
    on inspection_change_request (tenant_id, template_object, created_at desc, id desc) where deleted_at is null;
create index inspection_change_request_tenant_object_to_act
    on inspection_change_request (tenant_id, template_object, template_id)
    where status in ('OPEN', 'ACCEPTED') and deleted_at is null;
-- The timeline of one set.
create index inspection_template_event_tenant_object_time
    on inspection_template_event (tenant_id, template_object, created_at desc, id) where deleted_at is null;

-- The guard of V075, with one more thing that never changes once written: the set a request belongs to.
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
            or new.header_id is distinct from old.header_id
            or new.template_object is distinct from old.template_object then
        raise exception 'change request guard: what a request of % says about its template, version, title, department, review record and set is immutable',
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

comment on column inspection_change_request.template_object is
    'The API name of the template object of the set this request belongs to; written once (ADR-0226).';
comment on column inspection_template_event.template_object is
    'The API name of the template object of the set this event belongs to (ADR-0226).';
