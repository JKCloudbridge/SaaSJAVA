-- S3 text, selective: an indexed text field whose values are unique.
\set i random(1, :big_n)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null
   and s_text_2 = 'user' || lpad((:i)::text, 9, '0') || '@example.test'
 order by id limit 50;
commit;
