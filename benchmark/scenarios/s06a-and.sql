-- S6: two indexed fields combined with AND.
\set d random(0, 49)
\set s random(30000, 116000)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null
   and s_text_1 = 'Dept ' || lpad((:d)::text, 2, '0')
   and s_num_1 between :s and :s + 2000
 order by id limit 50;
commit;
