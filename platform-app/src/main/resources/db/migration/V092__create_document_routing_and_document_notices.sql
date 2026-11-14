-- Routing of documents to applications by their number, and the notice that tells the creator a document was published
-- (Functional track, Management Core part 1.1, ADR-0259). Expand only: a table is added and two lists the database
-- checks are widened; nothing is removed or renamed.
--
--   inspection_document_routing  One row for each rule an organization set in Setup, Numbering, Application routing:
--                                "a document whose Identification is X (and whose Part A is Y, or whatever Part A when
--                                none is given) also belongs to the application Z". The platform reads the rules when a
--                                document's number is made and writes the application on the document once; a rule that
--                                is changed or removed later never moves a document that was numbered before.
--   kind DOCUMENT_PUBLISHED      The notice a document's creator gets when it is published. It stores facts only (which
--                                event and where the document's page lives); the words are built when it is read.
--   category DOCUMENTS           What a person switches on or off, per channel, for these notices and their mail.
--
-- What must hold even if the application had a bug:
--   * the table is tenant-scoped with forced row level security and has no system scope;
--   * a rule is one per combination: one live row for each (Identification, Part A), the empty Part A standing for
--     "every Part A"; two rules for one combination cannot exist;
--   * the application name is a lower case key and the Identification and Part A have the shapes the template's own
--     fields have, so nothing typed freely is stored beyond them;
--   * a rule moves to no other organization and is removed by marking it (soft delete), like the number formats.
-- The Identification and Part A are the organization's own text: they never reach a log or an audit record (the audit
-- says the kind of change and the application's key only). The table feeds no answer of the permission module and no
-- metadata, so it needs no security-version or metadata-version trigger.

create table inspection_document_routing (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    category_code   text        not null,
    part_a          text        not null default '',
    application     text        not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint inspection_document_routing_category_shape check (category_code ~ '^[A-Za-z0-9_-]{1,10}$'),
    constraint inspection_document_routing_part_shape check (part_a ~ '^[A-Z0-9-]{0,10}$'),
    constraint inspection_document_routing_application_shape check (application ~ '^[a-z][a-z0-9-]{0,59}$')
);

create unique index inspection_document_routing_combination
    on inspection_document_routing (tenant_id, category_code, part_a) where deleted_at is null;

create function platform_inspection_document_routing_guard() returns trigger
    language plpgsql
as $guard$
begin
    -- Messages name the table only, never a value.
    if tg_op = 'UPDATE' then
        if new.category_code is distinct from old.category_code or new.part_a is distinct from old.part_a then
            raise exception 'routing guard: the combination of a rule in % never changes; remove it and make another',
                tg_table_name using errcode = 'check_violation';
        end if;
        if old.deleted_at is not null then
            raise exception 'routing guard: a removed rule in % is not changed', tg_table_name
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create trigger inspection_document_routing_guard before update on inspection_document_routing
    for each row execute function platform_inspection_document_routing_guard();
create trigger inspection_document_routing_row_guard before insert or update on inspection_document_routing
    for each row execute function platform_row_guard();
create trigger inspection_document_routing_tenant_guard before update on inspection_document_routing
    for each row execute function platform_tenant_guard();
alter table inspection_document_routing enable row level security;
alter table inspection_document_routing force row level security;
create policy inspection_document_routing_insert on inspection_document_routing for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_document_routing_select on inspection_document_routing for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_document_routing_update on inspection_document_routing for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_document_routing_delete on inspection_document_routing for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_document_routing is
    'Which application a document also belongs to, by Identification and Part A. Read when a number is made (ADR-0259).';

alter table notification drop constraint notification_kind_known;
alter table notification add constraint notification_kind_known check (kind in (
    'APPROVAL_TASK', 'APPROVAL_REMINDER', 'APPROVAL_DECIDED', 'WORKFLOW_NOTICE', 'TRIAL_NOTICE', 'CHANGE_REQUEST',
    'DOCUMENT_PUBLISHED'));

alter table notification_preference drop constraint notification_preference_category_known;
alter table notification_preference add constraint notification_preference_category_known check (
    category in ('APPROVALS', 'WORKFLOWS', 'ACCOUNT', 'REQUESTS', 'DOCUMENTS'));
