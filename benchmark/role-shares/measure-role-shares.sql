-- FA2B gap 6: the cost of the role chain in the share condition of a list. A scratch database, a reduced schema with the
-- same indexes as record_share and object_record (no row level security: it adds the same tenant test to both shapes).
\set ON_ERROR_STOP on
drop database if exists scratch_fa2b;
create database scratch_fa2b;
\c scratch_fa2b

create extension if not exists pgcrypto;

create table object_record (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    object_api_name text not null,
    owner_id uuid not null,
    deleted_at timestamptz,
    payload jsonb not null default '{}'
);
create index object_record_live on object_record (tenant_id, object_api_name, id) where deleted_at is null;
create index object_record_owner on object_record (tenant_id, object_api_name, owner_id, id) where deleted_at is null;

create table record_share (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    object_api_name text not null,
    record_id uuid not null references object_record (id),
    grantee_type text not null,
    grantee_id uuid not null,
    access_level smallint not null,
    deleted_at timestamptz
);
create unique index record_share_tenant_record_grantee on record_share (tenant_id, record_id, grantee_id) where deleted_at is null;
create index record_share_tenant_grantee on record_share (tenant_id, object_api_name, grantee_id, access_level, record_id) where deleted_at is null;

-- One organization of interest and a lot of noise from others, so that the tenant test matters.
create table fixed (name text primary key, id uuid);
insert into fixed values ('tenant', gen_random_uuid()), ('me', gen_random_uuid()), ('group1', gen_random_uuid()), ('group2', gen_random_uuid());
create table roles (n int primary key, id uuid default gen_random_uuid());
insert into roles select g from generate_series(1, 30) g;
create table people (n int primary key, id uuid default gen_random_uuid());
insert into people select g from generate_series(1, 500) g;

insert into object_record (tenant_id, object_api_name, owner_id)
select (select id from fixed where name = 'tenant'), 'Item__c',
       case when g % 500 = 0 then (select id from fixed where name = 'me') else (select id from people where n = 1 + g % 499) end
from generate_series(1, 20000) g;
insert into object_record (tenant_id, object_api_name, owner_id)
select gen_random_uuid(), 'Item__c', gen_random_uuid() from generate_series(1, 60000) g;

-- 2,000 shares to people and groups (the shape that exists today).
insert into record_share (tenant_id, object_api_name, record_id, grantee_type, grantee_id, access_level)
select (select id from fixed where name = 'tenant'), 'Item__c', r.id, 'USER',
       case when g % 40 = 0 then (select id from fixed where name = 'me') else (select id from people where n = 1 + g % 499) end, 1
from (select id, row_number() over (order by id) g from object_record where tenant_id = (select id from fixed where name = 'tenant') limit 2000) r;
analyze;

create or replace function bench(label text, sql text, runs int) returns void language plpgsql as $f$
declare t0 timestamptz; begin
  t0 := clock_timestamp();
  for i in 1..runs loop execute sql; end loop;
  raise notice '% : % ms per list (first page of 51)', label, round((extract(epoch from clock_timestamp() - t0) * 1000 / runs)::numeric, 3);
end $f$;

-- the member: user "me", groups 1 and 2, role 5 whose chain is roles 5, 3, 2, 1
create or replace function member_sql(shape text) returns text language plpgsql as $f$
declare t uuid := (select id from fixed where name = 'tenant');
        me uuid := (select id from fixed where name = 'me');
        g1 uuid := (select id from fixed where name = 'group1');
        g2 uuid := (select id from fixed where name = 'group2');
        r5 uuid := (select id from roles where n = 5);
        chain text := (select string_agg(id::text, ',') from roles where n in (5, 3, 2, 1));
        everyone text := (select string_agg(x::text, ',') from (select me::text x union select g1::text union select g2::text union select id::text from roles where n in (5, 3, 2, 1)) q);
        old_ids text := me || ',' || g1 || ',' || g2;
        base text := 'select id from object_record where tenant_id = ''' || t || ''' and object_api_name = ''Item__c'' and deleted_at is null and (owner_id = ''' || me || ''' or id = any(array(select s.record_id from record_share s where s.tenant_id = ''' || t || ''' and s.object_api_name = ''Item__c'' and s.deleted_at is null and ';
        tail text := ' and s.access_level >= 1))) order by id limit 51';
begin
  if shape = 'old' then
    return base || 's.grantee_id = any(string_to_array(''' || old_ids || ''', '','')::uuid[])' || tail;
  elsif shape = 'new' then
    return base || 's.grantee_id = any(string_to_array(''' || everyone || ''', '','')::uuid[]) and ('
      || '(s.grantee_type = ''USER'' and s.grantee_id = any(string_to_array(''' || me || ''', '','')::uuid[])) or '
      || '(s.grantee_type = ''GROUP'' and s.grantee_id = any(string_to_array(''' || g1 || ',' || g2 || ''', '','')::uuid[])) or '
      || '(s.grantee_type = ''ROLE'' and s.grantee_id = any(string_to_array(''' || r5 || ''', '','')::uuid[])) or '
      || '(s.grantee_type = ''ROLE_AND_SUBORDINATES'' and s.grantee_id = any(string_to_array(''' || chain || ''', '','')::uuid[])))' || tail;
  else
    return base || '(s.grantee_type = ''USER'' and s.grantee_id = any(string_to_array(''' || me || ''', '','')::uuid[])) or '
      || '(s.grantee_type = ''GROUP'' and s.grantee_id = any(string_to_array(''' || g1 || ',' || g2 || ''', '','')::uuid[])) or '
      || '(s.grantee_type = ''ROLE'' and s.grantee_id = any(string_to_array(''' || r5 || ''', '','')::uuid[])) or '
      || '(s.grantee_type = ''ROLE_AND_SUBORDINATES'' and s.grantee_id = any(string_to_array(''' || chain || ''', '','')::uuid[]))' || tail;
  end if;
end $f$;

\echo === A. no role shares exist (2,000 shares to people)
select bench('old shape (grantee id only)', member_sql('old'), 300);
select bench('new shape (id first, kinds second)', member_sql('new'), 300);
select bench('branches only (no coarse test)', member_sql('branches'), 300);

-- 6,000 shares to roles: 200 records each for 30 roles, 100 of them to the member's own role chain
insert into record_share (tenant_id, object_api_name, record_id, grantee_type, grantee_id, access_level)
select (select id from fixed where name = 'tenant'), 'Item__c', r.id,
       case when g % 2 = 0 then 'ROLE' else 'ROLE_AND_SUBORDINATES' end,
       (select id from roles where n = 1 + g % 30), 1
from (select id, row_number() over (order by id desc) g from object_record where tenant_id = (select id from fixed where name = 'tenant') limit 6000) r
on conflict do nothing;
analyze;
select count(*) as role_shares from record_share where grantee_type in ('ROLE', 'ROLE_AND_SUBORDINATES');

\echo === B. 6,000 role shares exist, the chain of the member matches about a seventh of them
select bench('old shape (ignores roles: what the member got before)', member_sql('old'), 300);
select bench('new shape (id first, kinds second)', member_sql('new'), 300);
select bench('branches only (no coarse test)', member_sql('branches'), 300);

\echo === the plan of the new shape
select member_sql('new') as new_statement \gset
explain (analyze, buffers, costs off, summary on) :new_statement;

\echo === C. with the kind in the index
create index record_share_tenant_kind_grantee on record_share (tenant_id, object_api_name, grantee_type, grantee_id, access_level, record_id) where deleted_at is null;
analyze;
select bench('new shape + kind index', member_sql('new'), 300);
select bench('branches only + kind index', member_sql('branches'), 300);
select member_sql('branches') as branch_statement \gset
explain (analyze, buffers, costs off, summary on) :branch_statement;
