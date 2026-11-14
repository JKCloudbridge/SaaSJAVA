-- Publishing a document to the plants, the reviewers chosen for each plant, and the claim of one fill-in for each
-- document, plant and month (Functional track, Management Core part 2A, MC2A, ADR-0260). Expand only; nothing is removed
-- or renamed.
--
--   inspection_plant_publication  One row for each plant a document is or was published to by its creator. It is the
--                                 memory of that act and nothing else: Document Management's own publish has its own
--                                 memory (inspection_publication, V066), so withdrawing a plant never removes a share
--                                 Document Management made and Document Management's unpublish never removes the
--                                 plants. made_share says whether the act made the read share itself, so withdrawing
--                                 takes back exactly that and never a share somebody gave by hand.
--   inspection_plant_reviewer     One row for each person or group chosen as a reviewer of a plant of a document. The
--                                 approver rule PUBLICATION_REVIEWERS reads it when a step becomes active, so a change
--                                 of reviewers never touches a request already running (its tasks were made then).
--   inspection_fill_period        The claim of one fill-in for a document, a plant and a month. Only a fill-in that has
--                                 a plant has a row, so the fill-ins of every other application are untouched. The
--                                 unique index is what stops two plant heads of one plant from starting the same month
--                                 at the same moment; the application checks first and says so in words.
--
-- What must hold even if the application had a bug:
--   * every table is tenant-scoped with forced row level security and no system scope;
--   * a document is published to a plant at most once at a time, a person or group is a reviewer of a plant of a
--     document at most once at a time, and a document, a plant and a month have at most one fill-in (unique indexes that
--     lead with the tenant); a revoked row does not count;
--   * nothing is ever deleted or soft-deleted, and once revoked a row never changes again;
--   * what a row says about its document, plant, group, person and share never changes;
--   * a fill-in claim never changes or goes away: a fill-in that is returned is edited, never made again.
-- The plant code is a code of the organization's list, and a reviewer is a person or group the organization chose, so
-- nothing a row holds is written to a log, an audit record or an event: the audit names the document by its identifier
-- and carries counts. None of the three tables feeds an answer of the permission module or the metadata, so none needs a
-- security-version or metadata-version trigger.

create table inspection_plant_publication (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    template_id   uuid        not null,
    plant         text        not null,
    group_id      uuid        not null,
    made_share    boolean     not null,
    revoked_at    timestamptz,
    revoked_by    uuid,
    ended_reason  text,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid,
    constraint inspection_plant_publication_plant_shape check (plant ~ '^[A-Za-z0-9_-]{1,10}$'),
    constraint inspection_plant_publication_end_known check (ended_reason is null or ended_reason in ('withdrawn', 'changed')),
    constraint inspection_plant_publication_end_whole check (
        (revoked_at is null and revoked_by is null and ended_reason is null)
        or (revoked_at is not null and revoked_by is not null and ended_reason is not null))
);

create unique index inspection_plant_publication_live
    on inspection_plant_publication (tenant_id, template_id, plant) where revoked_at is null and deleted_at is null;
create index inspection_plant_publication_tenant_template
    on inspection_plant_publication (tenant_id, template_id, created_at);

create table inspection_plant_reviewer (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    template_id   uuid        not null,
    plant         text        not null,
    subject_kind  text        not null,
    subject_id    uuid        not null,
    revoked_at    timestamptz,
    revoked_by    uuid,
    ended_reason  text,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid,
    constraint inspection_plant_reviewer_plant_shape check (plant ~ '^[A-Za-z0-9_-]{1,10}$'),
    constraint inspection_plant_reviewer_kind_known check (subject_kind in ('USER', 'GROUP')),
    constraint inspection_plant_reviewer_end_known check (ended_reason is null or ended_reason in ('removed', 'withdrawn')),
    constraint inspection_plant_reviewer_end_whole check (
        (revoked_at is null and revoked_by is null and ended_reason is null)
        or (revoked_at is not null and revoked_by is not null and ended_reason is not null))
);

create unique index inspection_plant_reviewer_live
    on inspection_plant_reviewer (tenant_id, template_id, plant, subject_kind, subject_id)
    where revoked_at is null and deleted_at is null;
create index inspection_plant_reviewer_tenant_template
    on inspection_plant_reviewer (tenant_id, template_id, plant, created_at);

create table inspection_fill_period (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    template_id   uuid        not null,
    fill_id       uuid        not null,
    plant         text        not null,
    period        text        not null,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid,
    constraint inspection_fill_period_plant_shape check (plant ~ '^[A-Za-z0-9_-]{1,10}$'),
    constraint inspection_fill_period_period_shape check (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

create unique index inspection_fill_period_one_per_month
    on inspection_fill_period (tenant_id, template_id, plant, period) where deleted_at is null;
create unique index inspection_fill_period_one_per_fill
    on inspection_fill_period (tenant_id, fill_id) where deleted_at is null;

create function platform_inspection_plant_publication_guard() returns trigger
    language plpgsql
as $guard$
begin
    -- Messages name the table only, never a value.
    if tg_op = 'INSERT' then
        if new.revoked_at is not null then
            raise exception 'plant publication guard: a row of % starts live', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- tg_op = 'UPDATE'
    if new.deleted_at is not null then
        raise exception 'plant publication guard: the rows of % are kept', tg_table_name
            using errcode = 'check_violation';
    end if;
    if old.revoked_at is not null then
        raise exception 'plant publication guard: a revoked row of % does not change', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.template_id is distinct from old.template_id or new.plant is distinct from old.plant
            or new.group_id is distinct from old.group_id or new.made_share is distinct from old.made_share then
        raise exception 'plant publication guard: what a row of % says about its document and plant is immutable',
            tg_table_name using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create function platform_inspection_plant_reviewer_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.revoked_at is not null then
            raise exception 'plant reviewer guard: a row of % starts live', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if new.deleted_at is not null then
        raise exception 'plant reviewer guard: the rows of % are kept', tg_table_name
            using errcode = 'check_violation';
    end if;
    if old.revoked_at is not null then
        raise exception 'plant reviewer guard: a revoked row of % does not change', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.template_id is distinct from old.template_id or new.plant is distinct from old.plant
            or new.subject_kind is distinct from old.subject_kind or new.subject_id is distinct from old.subject_id then
        raise exception 'plant reviewer guard: what a row of % says about its document and reviewer is immutable',
            tg_table_name using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create function platform_inspection_fill_period_guard() returns trigger
    language plpgsql
as $guard$
begin
    -- A claim is written once with the fill-in it belongs to and never changes.
    if tg_op = 'UPDATE' then
        raise exception 'fill period guard: a row of % never changes', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create function platform_inspection_plant_tables_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'plant publication guard: the rows of % are kept', tg_table_name
        using errcode = 'check_violation';
end;
$guard$;

create trigger inspection_plant_publication_guard before insert or update on inspection_plant_publication
    for each row execute function platform_inspection_plant_publication_guard();
create trigger inspection_plant_publication_no_delete before delete on inspection_plant_publication
    for each row execute function platform_inspection_plant_tables_no_delete();
create trigger inspection_plant_publication_row_guard before insert or update on inspection_plant_publication
    for each row execute function platform_row_guard();
create trigger inspection_plant_publication_tenant_guard before update on inspection_plant_publication
    for each row execute function platform_tenant_guard();

create trigger inspection_plant_reviewer_guard before insert or update on inspection_plant_reviewer
    for each row execute function platform_inspection_plant_reviewer_guard();
create trigger inspection_plant_reviewer_no_delete before delete on inspection_plant_reviewer
    for each row execute function platform_inspection_plant_tables_no_delete();
create trigger inspection_plant_reviewer_row_guard before insert or update on inspection_plant_reviewer
    for each row execute function platform_row_guard();
create trigger inspection_plant_reviewer_tenant_guard before update on inspection_plant_reviewer
    for each row execute function platform_tenant_guard();

create trigger inspection_fill_period_guard before insert or update on inspection_fill_period
    for each row execute function platform_inspection_fill_period_guard();
create trigger inspection_fill_period_no_delete before delete on inspection_fill_period
    for each row execute function platform_inspection_plant_tables_no_delete();
create trigger inspection_fill_period_row_guard before insert or update on inspection_fill_period
    for each row execute function platform_row_guard();
create trigger inspection_fill_period_tenant_guard before update on inspection_fill_period
    for each row execute function platform_tenant_guard();

alter table inspection_plant_publication enable row level security;
alter table inspection_plant_publication force row level security;
create policy inspection_plant_publication_insert on inspection_plant_publication for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_plant_publication_select on inspection_plant_publication for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_plant_publication_update on inspection_plant_publication for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_plant_publication_delete on inspection_plant_publication for delete
    using (tenant_id = (select platform_current_tenant()));

alter table inspection_plant_reviewer enable row level security;
alter table inspection_plant_reviewer force row level security;
create policy inspection_plant_reviewer_insert on inspection_plant_reviewer for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_plant_reviewer_select on inspection_plant_reviewer for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_plant_reviewer_update on inspection_plant_reviewer for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_plant_reviewer_delete on inspection_plant_reviewer for delete
    using (tenant_id = (select platform_current_tenant()));

alter table inspection_fill_period enable row level security;
alter table inspection_fill_period force row level security;
create policy inspection_fill_period_insert on inspection_fill_period for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_fill_period_select on inspection_fill_period for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_fill_period_update on inspection_fill_period for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_fill_period_delete on inspection_fill_period for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_plant_publication is
    'The plants a document was published to by its creator (the memory of that act, apart from the one of Document Management). ADR-0260.';
comment on table inspection_plant_reviewer is
    'The people and groups chosen as reviewers of a plant of a document. Read when a review step becomes active. ADR-0260.';
comment on table inspection_fill_period is
    'The claim of one fill-in for a document, a plant and a month. Only a fill-in that has a plant has a row. ADR-0260.';

-- The notice that tells the people of a plant that a document was made available to them.
alter table notification drop constraint notification_kind_known;
alter table notification add constraint notification_kind_known check (kind in (
    'APPROVAL_TASK', 'APPROVAL_REMINDER', 'APPROVAL_DECIDED', 'WORKFLOW_NOTICE', 'TRIAL_NOTICE', 'CHANGE_REQUEST',
    'DOCUMENT_PUBLISHED', 'DOCUMENT_AT_PLANT'));
