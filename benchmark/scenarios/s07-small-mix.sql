-- S7: what a small organization does: read one of its records and filter its small object. The tenant is random.
\set k random(2, :small_t + 1)
\set j random(1, :small_n)
\set d random(0, 49)
\set seq :small_base + :k * 100000 + :j
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(:k)::text, true);
select id, version, owner_id, record_type, payload from object_record
 where tenant_id = bench_tenant(:k) and object_api_name = 'Employee__c' and id = bench_id(:seq) and deleted_at is null;
select id, version, payload from object_record
 where tenant_id = bench_tenant(:k) and object_api_name = 'Employee__c' and deleted_at is null
   and s_text_1 = 'Dept ' || lpad((:d)::text, 2, '0')
 order by id limit 50;
commit;
