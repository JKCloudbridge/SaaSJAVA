-- Carried note (Sprint 2), step 2 of 3: the read of S1 with the tenant setting only (one extra statement).
\set i random(1, :big_n)
begin;
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, owner_id, record_type, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i) and deleted_at is null;
commit;
