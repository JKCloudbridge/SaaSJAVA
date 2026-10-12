-- S10: many-to-many, reverse: the cases linked to one employee.
\set i random(1, :big_n)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select r.id, r.version, r.payload
  from object_record_link l
  join object_record r on r.tenant_id = l.tenant_id and r.id = l.left_id and r.deleted_at is null
 where l.tenant_id = bench_tenant(1) and l.junction = 'CaseEmployee__c'
   and l.right_id = bench_id(:i) and l.deleted_at is null
 order by r.id limit 50;
commit;
