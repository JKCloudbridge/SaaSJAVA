-- S4 (b): filter on a field that is NOT indexed, with a value that matches one record: the whole object is scanned.
\set i random(1, :case_n)
\set seq :case_base + :i
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Case__c' and deleted_at is null
   and (payload ->> 'Code__c') = 'C' || lpad((:seq)::text, 9, '0')
 order by id limit 50;
commit;
