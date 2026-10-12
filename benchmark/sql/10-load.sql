-- Gate G1 data load. Run by run-benchmark.ps1 as the owner role with these psql variables:
--   big_n small_t small_n case_n users tenants
-- Everything is generated inside the database (generate_series); nothing goes through the application.
--
-- Sequence numbers (they make the identifiers deterministic, so a scenario can name "record n" without a lookup):
--   Employee__c of the large organization (tenant 1):     1 .. big_n
--   Case__c of the large organization:                    100000000 + 1 .. case_n
--   small organization k (2 .. small_t + 1), object o:    200000000 + k * 100000 + o * 50000 + j      (j in 1 .. small_n)
--   users:                                                300000000 + n
--
-- The loader bypasses triggers and constraints on purpose (session_replication_role = replica): the rules they carry
-- (base columns, tenant guard) are the same for every row, and the loader supplies correct values itself. The
-- scenarios, which are what is measured, run with all of them on.

\set ON_ERROR_STOP on
\timing on
set session_replication_role = replica;
set synchronous_commit = off;

-- organizations: tenant 1 is the large one, 2 .. small_t + 1 the small ones, the rest are empty organizations that make
-- the host lookup realistic
insert into tenant (id, slug, display_name, status, created_by, updated_by)
select bench_tenant(k),
       case when k = 1 then 'bench-large' else 'bench-' || lpad(k::text, 5, '0') end,
       'Organization ' || k, 'ACTIVE', bench_tenant(0), bench_tenant(0)
  from generate_series(1, :tenants) k;

insert into security_version (tenant_id, version, created_by, updated_by)
select bench_tenant(k), 0, bench_tenant(0), bench_tenant(0) from generate_series(1, :tenants) k;

-- records of the large organization
insert into object_record (id, tenant_id, object_api_name, owner_id, payload, s_text_1, s_text_2, s_num_1, s_date_1, s_ref_1,
                           created_by, updated_by)
select bench_id(g), bench_tenant(1), 'Employee__c', bench_tenant(0), r.payload, r.s_text_1, r.s_text_2, r.s_num_1, r.s_date_1,
       r.s_ref_1, bench_tenant(0), bench_tenant(0)
  from generate_series(1::bigint, :big_n) g cross join lateral bench_row(g) r;

insert into object_record (id, tenant_id, object_api_name, owner_id, payload, s_text_1, s_text_2, s_num_1, s_date_1, s_ref_1,
                           created_by, updated_by)
select bench_id(100000000 + g), bench_tenant(1), 'Case__c', bench_tenant(0), r.payload, r.s_text_1, r.s_text_2, r.s_num_1,
       r.s_date_1, r.s_ref_1, bench_tenant(0), bench_tenant(0)
  from generate_series(1::bigint, :case_n) g cross join lateral bench_row(100000000 + g) r;

-- records of the small organizations
insert into object_record (id, tenant_id, object_api_name, owner_id, payload, s_text_1, s_text_2, s_num_1, s_date_1, s_ref_1,
                           created_by, updated_by)
select bench_id(200000000::bigint + k * 100000 + o * 50000 + j), bench_tenant(k),
       case o when 0 then 'Employee__c' else 'Case__c' end, bench_tenant(0),
       r.payload, r.s_text_1, r.s_text_2, r.s_num_1, r.s_date_1, r.s_ref_1, bench_tenant(0), bench_tenant(0)
  from generate_series(2, :small_t + 1) k, generate_series(0, 1) o, generate_series(1, :small_n) j
       cross join lateral bench_row(200000000::bigint + k * 100000 + o * 50000 + j) r;

-- many-to-many: every case points at two employees
insert into object_record_link (tenant_id, junction, left_id, right_id, created_by, updated_by)
select bench_tenant(1), 'CaseEmployee__c', bench_id(100000000 + g),
       bench_id(1 + (g * 13) % :big_n), bench_tenant(0), bench_tenant(0)
  from generate_series(1::bigint, :case_n) g;
insert into object_record_link (tenant_id, junction, left_id, right_id, created_by, updated_by)
select bench_tenant(1), 'CaseEmployee__c', bench_id(100000000 + g),
       bench_id(1 + (g * 13 + :big_n / 2) % :big_n), bench_tenant(0), bench_tenant(0)
  from generate_series(1::bigint, :case_n) g;

-- people, their memberships and their sign-in grants (the carried notes of Sprints 3 and 5)
insert into platform_user (id, email, display_name, status, email_verified_at, created_by, updated_by)
select bench_id(300000000 + n), 'user-' || n || '@example.test', 'User ' || n, 'ACTIVE', now(),
       bench_tenant(0), bench_tenant(0)
  from generate_series(1::bigint, :users) n;

insert into membership (tenant_id, user_id, status, created_by, updated_by)
select bench_tenant(case when n % 10 = 0 then 1 else 2 + (n % :small_t)::integer end),
       bench_id(300000000 + n), 'ACTIVE', bench_tenant(0), bench_tenant(0)
  from generate_series(1::bigint, :users) n;

-- two grants per person; the first half are alive, the others expired
insert into oauth2_authorization (registered_client_id, principal_name, authorization_grant_type, authorized_scopes,
                                  access_token_value, access_token_issued_at, access_token_expires_at,
                                  access_token_type, access_token_scopes, user_id, bound_tenant_id,
                                  security_version, created_by, updated_by)
select 'bench-client', 'user-' || (1 + g % :users) || '@example.test', 'authorization_code', 'api',
       encode(sha256(('tok' || g)::bytea), 'hex'),
       now() - interval '5 minutes',
       case when g <= :users then now() + interval '1 hour' else now() - interval '1 hour' end,
       'Bearer', 'api', bench_id(300000000 + 1 + g % :users),
       bench_tenant(case when (1 + g % :users) % 10 = 0 then 1 else 2 + ((1 + g % :users) % :small_t)::integer end),
       0, bench_tenant(0), bench_tenant(0)
  from generate_series(1::bigint, 2 * :users) g;
