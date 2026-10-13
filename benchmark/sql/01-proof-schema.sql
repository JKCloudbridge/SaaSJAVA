-- Gate G1 (ADR-0082) helpers of the benchmark. Since Sprint 16 the record table is the real one of the repository
-- (migrations V036, V041, V042): this file no longer creates it. It adds the association table of the many-to-many
-- scenario, the helper functions of the data generator and the second role of the "row level security off"
-- comparison, after the repository's migrations and the manual role migration M001, as the owner role.
--
-- The secondary indexes of the record table are dropped before the load and created again afterwards from their own
-- stored definitions (see container/setup.sh): building an index over loaded rows is much faster than maintaining
-- it row by row, and the finished index is exactly the one the migration made.

create function bench_tenant(k integer) returns uuid
    language sql immutable parallel safe
as $$ select ('00000000-0000-4000-8000-' || lpad(k::text, 12, '0'))::uuid $$;

-- A deterministic identifier shaped like a version 7 UUID (time-ordered by the sequence number), so a scenario can
-- name "record number n" without a lookup, and the index locality is that of the real identifiers.
create function bench_id(seq bigint) returns uuid
    language sql immutable parallel safe
as $$
    select (lpad(to_hex(1760000000000 + seq), 12, '0') || '7' || substr(md5(seq::text), 1, 3) || '8'
            || substr(md5(seq::text), 4, 3) || substr(md5(seq::text), 7, 12))::uuid
$$;

-- The payload of one generated record: 14 fields, about 700 bytes.
create function bench_payload(seq bigint) returns jsonb
    language sql immutable parallel safe
as $$
    select jsonb_build_object(
        'Name__c',       'Person ' || seq,
        'Email__c',      'user' || lpad(seq::text, 9, '0') || '@example.test',
        'Department__c', 'Dept ' || lpad((seq % 50)::text, 2, '0'),
        'Salary__c',     30000 + (seq * 7919 % 90000),
        'HireDate__c',   to_char(date '2015-01-01' + ((seq * 31) % 3650)::integer, 'YYYY-MM-DD'),
        'Manager__c',    bench_id(1 + (seq % 20000))::text,
        'Active__c',     (seq % 7 <> 0),
        'Stage__c',      (array['New', 'Open', 'Won', 'Lost', 'Hold'])[1 + (seq % 5)::integer],
        'Region__c',     'R' || (seq % 10),
        'Code__c',       'C' || lpad(seq::text, 9, '0'),
        'Notes__c',      repeat(md5(seq::text), 6),
        'City__c',       'City ' || (seq % 400),
        'Rating__c',     seq % 100,
        'Counter__c',    0)
$$;

-- One record as the generator makes it: the JSON payload (the complete record) and the typed copies ("slots") of the
-- fields that are flagged "indexed". Under row level security PostgreSQL only uses an index for a filter whose
-- functions are leakproof; the JSON field operator and comparisons of decimal numbers are not, comparisons of text,
-- whole numbers, floating point numbers, dates, timestamps, booleans and identifiers are. That is why an indexed field
-- is copied into a plain typed column (see ADR-0082, amendment 1).
create function bench_row(seq bigint, out payload jsonb, out s_text_1 text, out s_text_2 text,
                          out s_num_1 double precision, out s_date_1 date, out s_ref_1 uuid)
    language sql immutable parallel safe
as $$
    select bench_payload(seq),
           'Dept ' || lpad((seq % 50)::text, 2, '0'),
           'user' || lpad(seq::text, 9, '0') || '@example.test',
           (30000 + (seq * 7919 % 90000))::double precision,
           date '2015-01-01' + ((seq * 31) % 3650)::integer,
           bench_id(1 + (seq % 20000))
$$;

create table object_record_link (
    id         uuid        primary key default uuidv7(),
    tenant_id  uuid        not null default platform_current_tenant() references tenant (id),
    junction   text        not null,
    left_id    uuid        not null,
    right_id   uuid        not null,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid
);

-- Both directions of the many-to-many scenarios (S10) are served by an index, as a junction would be in the product.
create index object_record_link_forward on object_record_link (tenant_id, junction, left_id, right_id) where deleted_at is null;
create index object_record_link_reverse on object_record_link (tenant_id, junction, right_id, left_id) where deleted_at is null;

create trigger object_record_link_row_guard before insert or update on object_record_link
    for each row execute function platform_row_guard();
create trigger object_record_link_tenant_guard before update on object_record_link
    for each row execute function platform_tenant_guard();

alter table object_record_link enable row level security;
alter table object_record_link force row level security;
create policy object_record_link_insert on object_record_link for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy object_record_link_select on object_record_link for select
    using (tenant_id = (select platform_current_tenant()));
create policy object_record_link_update on object_record_link for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy object_record_link_delete on object_record_link for delete
    using (tenant_id = (select platform_current_tenant()));

-- The role of the "row level security off" comparison: same rights as the application role, but it bypasses the
-- policies. It exists only in this throwaway database.
create role bench_bypass login bypassrls;
grant usage on schema public to bench_bypass;
grant select, insert, update, delete on all tables in schema public to bench_bypass;
revoke all on table public.flyway_schema_history from bench_bypass;

create extension if not exists pgstattuple;
